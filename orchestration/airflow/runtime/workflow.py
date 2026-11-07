"""Day 42 finite, allowlisted workers; no activation and no legacy feature writer."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(os.environ.get("PYMK_WORKFLOW_ROOT", "/opt/airflow/artifacts"))
PYTHON = "/opt/pymk/trainer/bin/python"
JAR = "/opt/pymk/pymk-batch.jar"
STEPS = {"feature": ("validate", "embeddings", "stage"),
         "candidate": ("validate", "export", "light", "heavy", "evaluate", "blend", "prepare"),
         "reconcile": ("validate", "reconcile"),
         "backfill": ("validate", "restore", "light", "heavy", "evaluate", "blend", "prepare")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def confined(path):
    path = Path(path).resolve()
    require(path.is_relative_to(ROOT.resolve()), "Artifact path escapes configured root")
    return path


def reference(path):
    path = confined(path)
    return {"path": str(path), "sha256": digest(path)}


def resolve(ref):
    require(isinstance(ref, dict) and set(ref) == {"path", "sha256"}, "Expected artifact reference")
    path = confined(ref["path"])
    require(digest(path) == ref["sha256"], "Artifact checksum mismatch")
    return path


def read(ref):
    return json.loads(resolve(ref).read_text())


def select_plan(workflow, supplied, start, end):
    if supplied is not None:
        plan = supplied
    else:
        require(start is not None and end is not None, "Scheduled interval required")
        key = end.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        envelope = confined(ROOT / "inbox" / workflow / (key + ".json"))
        plan = json.loads(envelope.read_text())
        document = read(plan)
        require(instant(document["intervalStart"]) == start and instant(document["intervalEnd"]) == end,
                "Scheduled manifest does not match data interval")
    require(read(plan)["workflow"] == workflow, "Manifest belongs to a different workflow")
    return plan


def instant(value):
    require(isinstance(value, str) and re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z", value), "Full UTC timestamp required")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def validate(plan, now=None):
    now = now or datetime.now(timezone.utc)
    kind = plan.get("workflow")
    require(kind in STEPS and plan.get("schemaVersion") == 1, "Unknown workflow/schema")
    common = {"schemaVersion", "workflow", "intervalStart", "intervalEnd", "outputVersion", "sourceRevision", "transform"}
    if kind == "reconcile":
        common -= {"transform", "sourceRevision"}
        specific = {"windowStart", "knowledgeCutoff"}
    else:
        specific = {"profiles"} if kind == "feature" else {"history", "evaluationPolicy", "blendPolicy", "context"}
    require(set(plan) == common | specific, "Unexpected or missing workflow fields")
    start, end = instant(plan["intervalStart"]), instant(plan["intervalEnd"])
    require(start < end <= now and (end-start).total_seconds() <= 7*86400, "Invalid or future interval")
    require(isinstance(plan["outputVersion"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", plan["outputVersion"]), "Unsafe output version")
    require(plan["outputVersion"] != "legacy", "Reserved output version")
    if kind == "reconcile":
        window = instant(plan["windowStart"])
        require(window < end and (end-window).total_seconds() <= 90*86400,
                "Reconciliation window must be positive and at most 90 days")
        require(end <= instant(plan["knowledgeCutoff"]) <= now, "Invalid ingestion watermark")
        return plan
    require(isinstance(plan["sourceRevision"], str) and re.fullmatch(r"[0-9]+", plan["sourceRevision"]), "Explicit source revision required")
    transform = read(plan["transform"])
    # The actual transform loader validates the complete schema during computation.
    cutoff = instant(transform["payload"]["metadata"]["fitCutoff"].replace("+00:00", "Z"))
    if kind == "feature":
        resolve(plan["profiles"])
        require(cutoff <= end, "Transform trained after feature snapshot")
    else:
        for name in ("evaluationPolicy", "blendPolicy", "context"):
            resolve(plan[name])
        history = plan["history"]
        require(isinstance(history, list) and len(history) >= 3, "At least three historical generations required")
        previous = None
        ids = set()
        for item in history:
            fields = {"releaseId", "featureAsOf", "labelWindowEnd"}
            if kind == "backfill":
                fields |= {"knowledgeCutoff", "featureSourceRevision", "labelSourceRevision", "light", "heavy"}
            require(set(item) == fields, "Invalid history entry")
            ident = item["releaseId"]
            require(isinstance(ident, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", ident) and ident != "legacy" and ident not in ids, "Invalid/duplicate historical release")
            ids.add(ident)
            snapshot, label_end = instant(item["featureAsOf"]), instant(item["labelWindowEnd"])
            require(cutoff <= snapshot < label_end <= end, "Labels are not mature at interval cutoff")
            require(previous is None or previous <= snapshot, "Overlapping/out-of-order label horizons")
            previous = label_end
            if kind == "backfill":
                knowledge = instant(item["knowledgeCutoff"])
                require(label_end <= knowledge <= end, "Historical knowledge cutoff outside mature interval")
                for field in ("featureSourceRevision", "labelSourceRevision"):
                    require(isinstance(item[field], str) and re.fullmatch(r"[0-9]+", item[field]), "Historical source revision required")
                for name, schema in (("light", 1), ("heavy", 2)):
                    pair = item[name]
                    require(set(pair) == {"csv", "provenance"}, "Frozen CSV and provenance required")
                    csv = resolve(pair["csv"])
                    provenance = read(pair["provenance"])
                    require(provenance["schemaVersion"] == schema
                            and provenance["releaseId"] == ident
                            and provenance["csvSha256"] == digest(csv)
                            and provenance["transformSha256"] == digest(resolve(plan["transform"]))
                            and instant(provenance["featureAsOf"].replace("+00:00", "Z")) == snapshot
                            and instant(provenance["labelWindowEnd"].replace("+00:00", "Z")) == label_end,
                            "Frozen export provenance mismatch")
                    if schema == 2:
                        require(str(provenance["featureSourceRevision"]) == item["featureSourceRevision"]
                                and str(provenance["labelSourceRevision"]) == item["labelSourceRevision"]
                                and provenance["exportedAt"].replace("+00:00", "Z") == item["knowledgeCutoff"],
                                "Historical revision/knowledge mismatch")
    return plan


def save(path, document):
    path = Path(path)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(document, stream, sort_keys=True, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)  # atomic publication; never replaces an existing identity
    finally:
        if temporary is not None: temporary.unlink(missing_ok=True)


def command(args, log, timeout=1200):
    # No shell, credential arguments, detached workers or success inferred from launch.
    with log.open("x", encoding="utf-8") as stream:
        subprocess.run([str(x) for x in args], stdout=stream, stderr=subprocess.STDOUT,
                       check=True, timeout=timeout)


def cli(module, args, directory, tracked=False):
    if tracked:
        uri = os.environ.get("PYMK_WORKFLOW_TRACKING_URI", "")
        require(bool(uri), "Tracked workflows require an injected MLflow URI")
        args = args + ["--tracking-dir", directory / "tracking", "--tracking-uri", uri]
    command([PYTHON, "-m", "pymk_training." + module, *args], directory / (module + ".log"))
    if tracked:
        bundles = list((directory / "tracking").glob("*/*/state.json"))
        require(len(bundles) == 1, "Expected one tracked computation")
        state = json.loads(bundles[0].read_text())
        require(state["status"] == "published", "Tracking publication not acknowledged")
        return bundles[0].parent


def batch(plan, job, parameters, directory, label):
    request = directory / (label + "-request.json")
    receipt = directory / (label + "-receipt.json")
    save(request, {"job": job, "parameters": {k: str(v) for k, v in parameters.items()}, "sourceRevision": plan.get("sourceRevision")})
    command(["java", "-Dloader.main=com.ahdyahmed.pymk.batch.WorkflowBatchRunner", "-cp", JAR,
             "org.springframework.boot.loader.launch.PropertiesLauncher", request, receipt], directory / (label + ".log"))
    require(json.loads(receipt.read_text())["status"] == "COMPLETED", "Missing completed batch receipt")


def execute(plan_ref, step, previous=None):
    plan = validate(read(plan_ref))
    sequence = STEPS[plan["workflow"]]
    require(step in sequence, "Unsupported workflow step")
    index = sequence.index(step)
    work = confined(ROOT / "workflows" / plan["outputVersion"])
    if index:
        prior = read(previous)
        require(prior["plan"] == plan_ref and prior["step"] == sequence[index-1], "Incorrect predecessor or changed plan")
        for ref in prior["artifacts"]:
            resolve(ref)
    else:
        require(previous is None, "Validation must not take a predecessor")
        prior = {"artifacts": [], "results": {}}
    directory = work / step
    directory.mkdir(parents=True, exist_ok=False)  # fail closed on partial/repeated work; Day 44 adds recovery
    results = dict(prior["results"])
    transform = resolve(plan["transform"]) if "transform" in plan else None
    version = plan["outputVersion"]
    if step == "reconcile":
        batch(plan, "liveFeatureReconciliationJob", {"windowStart": plan["windowStart"],
            "windowEnd": plan["intervalEnd"], "knowledgeCutoff": plan["knowledgeCutoff"],
            "runId": version}, directory, "reconcile")
    elif step == "restore":
        # Copy only retained exports, never query present-day profiles or labels.
        results["lightInputs"], results["heavyInputs"] = [], []
        for i, item in enumerate(plan["history"]):
            for name in ("light", "heavy"):
                output = directory / f"{name}-{i}.csv"
                with output.open("xb") as stream:
                    stream.write(resolve(item[name]["csv"]).read_bytes())
                save(Path(str(output) + ".provenance.json"), read(item[name]["provenance"]))
                results[name + "Inputs"].append(str(output))
    elif step == "embeddings":
        vectors, manifest = directory / "vectors.csv", directory / "release.json"
        cli("profile_embeddings", ["--input", resolve(plan["profiles"]), "--artifact", transform,
            "--output", vectors, "--as-of", plan["intervalEnd"]], directory)
        cli("release_manifest", ["--release-id", version, "--transform", transform,
            "--vectors", vectors, "--output", manifest], directory)
        require(manifest.is_file() and vectors.is_file(), "Missing embedding outputs")
        results["manifest"] = str(manifest)
    elif step == "stage":
        batch(plan, "embeddingRefreshJob", {"manifestPath": results["manifest"]}, directory, "stage")
    elif step == "export":
        results["lightInputs"], results["heavyInputs"] = [], []
        for i, item in enumerate(plan["history"]):
            for action, name in (("export", "light"), ("export-v2", "heavy")):
                output = directory / f"{name}-{i}.csv"
                params = {"action": action, "releaseId": item["releaseId"], "labelWindowEnd": item["labelWindowEnd"], "outputPath": output}
                if action == "export-v2": params["datasetId"] = f"{version}-{i}"
                batch(plan, "servingReleaseJob", params, directory, f"{name}-{i}")
                sidecar = json.loads(Path(str(output) + ".provenance.json").read_text())
                require(instant(sidecar["featureAsOf"].replace("+00:00", "Z")) == instant(item["featureAsOf"])
                        and instant(sidecar["labelWindowEnd"].replace("+00:00", "Z")) == instant(item["labelWindowEnd"])
                        and sidecar["releaseId"] == item["releaseId"]
                        and sidecar["transformSha256"] == digest(transform)
                        and sidecar["csvSha256"] == digest(output), "Export provenance mismatch")
                results[name + "Inputs"].append(str(output))
    elif step in ("light", "heavy"):
        args = [value for path in results[step + "Inputs"] for value in ("--input", path)]
        args += ["--embedding-transform", transform, "--output-dir", directory / "model", "--model-version", version + "-" + step]
        if step == "heavy": args += ["--l1-model", results["lightModel"], "--l1-metadata", results["lightMetadata"]]
        bundle = cli("train_" + ("light" if step == "light" else "heavy") + "_ranker", args, directory, True)
        results[step + "Bundle"] = str(bundle)
        results[step + "Model"] = str(directory / "model" / (version + "-" + step + ".onnx"))
        results[step + "Metadata"] = str(directory / "model" / (version + "-" + step + ".metadata.json"))
        metadata = json.loads(Path(results[step + "Metadata"]).read_text())
        require(metadata["onnx"]["sha256"] == digest(results[step + "Model"]), "Model output checksum mismatch")
    elif step in ("evaluate", "blend"):
        args = [value for path in results["heavyInputs"] for value in ("--input", path)]
        args += ["--model", results["heavyModel"], "--metadata", results["heavyMetadata"],
                 "--l1-model", results["lightModel"], "--l1-metadata", results["lightMetadata"]]
        if step == "evaluate":
            args += ["--policy", resolve(plan["evaluationPolicy"]), "--output", directory / "evaluation.json"]
        else:
            args += ["--policy", resolve(plan["blendPolicy"]), "--context", resolve(plan["context"]),
                     "--output-dir", directory / "tuning"]
        results[step + "Bundle"] = str(cli("evaluate_heavy_ranker" if step == "evaluate" else "tune_blend", args, directory, True))
    elif step == "prepare":
        for kind in ("light", "heavy"):
            logs = directory / (kind + "-logs")
            logs.mkdir()
            cli("registry", ["prepare", "--bundle", results[kind + "Bundle"], "--output-dir", directory / kind,
                "--java-verifier-jar", JAR, "--lineage-bundle", results["evaluateBundle"],
                "--lineage-bundle", results["blendBundle"]], logs)
            require((directory / kind / "candidate.json").is_file(), "Missing prepared candidate")
        report = json.loads((work / "evaluate" / "evaluation.json").read_text())
        results["qualityGate"] = report["qualityGate"]["status"]
        results["activationEligible"] = False  # approval/sync belongs to Day 44
    validate(read(plan_ref))  # Detect input mutation during the worker, too.
    for ref in prior["artifacts"]:
        resolve(ref)
    artifacts = prior["artifacts"] + [reference(p) for p in sorted(directory.rglob("*")) if p.is_file() and p.suffix != ".log"]
    receipt = directory / "result.json"
    save(receipt, {"schemaVersion": 1, "plan": plan_ref, "step": step, "artifacts": artifacts,
                   "results": results, "automaticPromotion": False})
    return reference(receipt)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True, type=Path)
    args = parser.parse_args()
    request = json.loads(confined(args.request).read_text())
    result = execute(request["plan"], request["step"], request.get("previous"))
    print(json.dumps(result))


if __name__ == "__main__": main()
