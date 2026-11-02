"""Opt-in, durable publication of privacy-filtered experiment evidence (not promotion)."""
from __future__ import annotations

import argparse
import csv
from contextlib import contextmanager
from datetime import datetime
import hashlib
from importlib.metadata import PackageNotFoundError, version
import json
import math
import os
from pathlib import Path
import platform
import re
import sys
import tempfile
import time
import uuid
from urllib.parse import urlsplit

OPERATIONS = {"profile", "light", "heavy", "evaluate", "blend"}
DEPENDENCIES = ("numpy", "onnx", "onnxruntime", "scikit-learn", "skl2onnx", "torch")
INPUT_FLAGS = ("embedding-transform", "l1-model", "l1-metadata", "model", "metadata", "policy", "context")
LINEAGE_KEYS = {"datasetId", "releaseId", "embeddingVersion", "csvSha256", "transformSha256",
                "cohortSha256", "releaseManifestSha256", "featureSourceRevision", "labelSourceRevision",
                "featureAsOf", "labelWindowEnd", "exportedAt", "fitCutoff", "fitProfilesSha256"}


class QualityGateNotMet(SystemExit):
    """Computation wrote a valid report, but its required quality gate failed."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def save(path, value):
    """Atomic local checkpoint; previous state survives interrupted writes."""
    path = Path(path)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".tracking-")
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(canonical(value)); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


@contextmanager
def lock(bundle):
    path = Path(bundle) / ".publish.lock"
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.close(fd)
        yield
    finally:
        path.unlink()


def identity():
    files = {p.name: digest(p) for p in sorted(Path(__file__).parent.glob("*.py"))}
    runtime = {"python": platform.python_version()}
    for package in DEPENDENCIES:
        try:
            runtime[package] = version(package)
        except PackageNotFoundError:
            runtime[package] = "not-installed"
    return {"sourceSha256": hashlib.sha256(canonical(files)).hexdigest(), "files": files, "runtime": runtime}


def specification(operation, argv):
    parser = argparse.ArgumentParser(add_help=False, allow_abbrev=False)
    parser.add_argument("--input", action="append", default=[], type=Path)
    for flag in INPUT_FLAGS + ("output-dir", "output", "artifact"):
        parser.add_argument("--" + flag, type=Path)
    parser.add_argument("--model-version", default="light-logreg-v1" if operation == "light" else None)
    parser.add_argument("--embedding-version")
    parser.add_argument("--as-of")
    parser.add_argument("--fit", action="store_true")
    parser.add_argument("--require-quality-gate", action="store_true")
    for name, default, kind in (("seed",42,int),("epochs",200,int),("hidden-size",16,int),
                               ("learning-rate",.01,float),("train-ratio",.7,float),("validation-ratio",.15,float)):
        parser.add_argument("--" + name, type=kind, default=default)
    args, unknown = parser.parse_known_args(argv)
    if unknown:
        raise ValueError("Unknown tracked command arguments; use full option names")
    inputs = [(f"input.{i}", p) for i, p in enumerate(args.input)]
    for flag in INPUT_FLAGS:
        value = getattr(args, flag.replace("-", "_"))
        if value is not None: inputs.append((flag, value))
    if operation == "profile" and not args.fit and args.artifact:
        inputs.append(("frozen-transform", args.artifact))
    for i, path in enumerate(args.input):
        sidecar = Path(str(path) + ".provenance.json")
        if operation != "profile": inputs.append((f"provenance.{i}", sidecar))
    parameters = {"seed": args.seed if operation == "heavy" else 42} if operation in {"light", "heavy", "profile"} else {}
    for key in {"light":("train_ratio","validation_ratio"), "heavy":("epochs","hidden_size","learning_rate"),
                "profile":("fit",), "evaluate":("require_quality_gate",), "blend":()}[operation]:
        parameters[key] = getattr(args, key)
    for key in ("model_version", "embedding_version", "as_of"):
        value = getattr(args, key)
        if value is not None:
            # Operational identifiers only, never arbitrary CLI strings, paths or URLs.
            if not re.fullmatch(r"[A-Za-z0-9_.:+-]{1,128}", value):
                raise ValueError("Invalid operational tracking identifier")
            parameters[key] = value
    outputs = []
    if operation in ("light", "heavy") and args.output_dir and args.model_version:
        outputs = [("onnx", args.output_dir / (args.model_version + ".onnx")),
                   ("report", args.output_dir / (args.model_version + ".metadata.json"))]
    elif operation == "profile":
        if args.output: outputs.append(("vectors", args.output))
        if args.artifact: outputs.append(("transform", args.artifact))
    elif operation == "evaluate" and args.output:
        outputs = [("report", args.output)]
    elif operation == "blend" and args.output_dir:
        outputs = [("report", args.output_dir / "report.json"),
                   ("configuration", args.output_dir / "application-blend.properties")]
    return inputs, outputs, parameters


def bindings(paths):
    return [{"role": role, "path": str(path.resolve()), "sha256": digest(path) if path.is_file() else None}
            for role, path in paths]


def public_bindings(records):
    return [{"role": item["role"], "sha256": item["sha256"]} for item in records]


def numeric_tree(value, prefix, metrics, unavailable):
    # These branches come from aggregate reports, never source rows or vocabularies.
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"knownGroupCounts", "inputs", "runtime", "reasons"}: continue
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key): continue
            numeric_tree(child, prefix + "." + key, metrics, unavailable)
    elif value is None:
        unavailable[prefix] = "not available in computation; see checksum-bound local report"
    elif type(value) in (int, float):
        if math.isfinite(value): metrics[prefix] = value
        else: unavailable[prefix] = "non-finite; not published as a number"


def summary(operation, inputs, outputs):
    result = {"metrics": {}, "unavailable": {}, "lineage": [], "qualityGate": "not_applicable"}
    for role, path in inputs:
        if role.startswith("provenance.") and path.is_file():
            value = json.loads(path.read_bytes())
            selected = {}
            for key in LINEAGE_KEYS:
                item = value.get(key)
                if isinstance(item, str) and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,128}", item):
                    selected[key] = item
            result["lineage"].append(selected)
    reports = dict(outputs)
    report_path = reports.get("report", reports.get("transform"))
    if report_path is None or not report_path.is_file(): return result
    report = json.loads(report_path.read_bytes())
    if operation == "profile": report = report["payload"]["metadata"]
    if "split" in report:
        result["split"] = report["split"]
        result["splitSha256"] = hashlib.sha256(canonical(report["split"])).hexdigest()
        cohorts = {name: [] for name in ("train", "validation", "test")}
        instant = lambda value: datetime.fromisoformat(value.replace("Z", "+00:00"))
        for role, path in inputs:
            if not role.startswith("input."): continue
            with path.open(encoding="utf-8", newline="") as stream:
                for row in csv.DictReader(stream):
                    at = instant(row["feature_as_of"])
                    for name, cohort in cohorts.items():
                        bounds = report["split"][name]
                        if instant(bounds["fromInclusive"]) <= at <= instant(bounds["throughInclusive"]):
                            cohort.append([row["member_id"], row["candidate_id"], at.isoformat(), row["label_window_end"]])
        result["partitionCohortSha256"] = {key: hashlib.sha256(canonical(sorted(value))).hexdigest() for key, value in cohorts.items()}
    for key in LINEAGE_KEYS:
        if key in report: result.setdefault("transform", {})[key] = report[key]
    for branch in ("metrics", "comparison", "partitions", "validation", "test", "selected"):
        if branch in report: numeric_tree(report[branch], branch, result["metrics"], result["unavailable"])
    # Policy is a validated numeric contract; hash plus numeric fields bind evaluation/tuning knobs.
    for branch in ("policy", "parameters"):
        if branch in report: numeric_tree(report[branch], branch, result.setdefault("parameters", {}), {})
    if operation == "profile":
        for key in ("dimensions", "effectiveDimensions", "fitMemberCount", "seed"):
            result["metrics"][key] = report[key]
    gate = report.get("qualityGate", {}).get("status")
    if gate in ("met", "not_met"): result["qualityGate"] = gate
    if "cohorts" in report:
        result["cohortsSha256"] = hashlib.sha256(canonical(report["cohorts"])).hexdigest()
    return result


def cli(operation, argv, work):
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(add_help=False, allow_abbrev=False)
    parser.add_argument("--tracking-dir", type=Path)
    parser.add_argument("--tracking-uri")
    options, remaining = parser.parse_known_args(argv)
    if options.tracking_dir is None:
        if options.tracking_uri: parser.error("--tracking-uri requires --tracking-dir")
        return work(remaining)  # No SDK import, filesystem scan or numerical change in default mode.
    if any(arg in remaining for arg in ("--help", "-h")): return work(remaining)
    inputs, outputs, parameters = specification(operation, remaining)
    before = bindings(inputs)
    spec = {"operation": operation, "parameters": parameters, "inputs": public_bindings(before), "build": identity()}
    logical = hashlib.sha256(canonical(spec)).hexdigest()
    attempt = uuid.uuid4().hex
    bundle = options.tracking_dir.resolve() / logical / attempt
    bundle.mkdir(parents=True, exist_ok=False)
    save(bundle / "state.json", {"status": "computing", "attempts": []})
    code, failure, caught = 0, None, None
    try:
        value = work(remaining)
        code = value if isinstance(value, int) else 0
    except BaseException as error:
        code = error.code if isinstance(error, SystemExit) and isinstance(error.code, int) else 2
        failure = type(error).__name__
        caught = error
    try:
        if bindings(inputs) != before: raise ValueError("Inputs changed during computation")
        completed = code == 0 or isinstance(caught, QualityGateNotMet)
        output_records = bindings(outputs) if completed else []
        report = summary(operation, inputs, outputs) if completed else {
            "metrics": {}, "unavailable": {}, "lineage": [], "qualityGate": "not_applicable"}
        if bindings(inputs) != before or (completed and bindings(outputs) != output_records):
            raise ValueError("Artifacts changed while binding evidence")
        if code == 0 and (not outputs or any(item["sha256"] is None for item in output_records)):
            raise ValueError("Missing completed outputs")
    except Exception as error:
        code, failure = 2, type(error).__name__
        report = {"metrics": {}, "unavailable": {}, "lineage": [], "qualityGate": "not_applicable"}
        output_records = []
    outcome = "not_met" if report["qualityGate"] == "not_met" else ("failed" if code else "succeeded")
    payload = {"schemaVersion": 1, "logicalWorkId": logical, "computationAttempt": attempt,
               "specification": spec, "outcome": outcome, "exitCode": code, "failureClass": failure,
               "evidence": report, "outputs": public_bindings(output_records), "automaticPromotion": False}
    save(bundle / "manifest.json", payload)
    save(bundle / "local.json", {"inputs": before, "outputs": output_records})
    save(bundle / "state.json", {"status": "unpublished", "attempts": [], "manifestSha256": digest(bundle / "manifest.json")})
    print(f"Tracking bundle: {bundle} (unpublished until acknowledged)")
    publication_failed = False
    if options.tracking_uri:
        try: publish(bundle, options.tracking_uri)
        except Exception:
            publication_failed = True
            print("Tracking publication failed; local outputs retained. Retry the bundle with pymk-publish-tracking.", file=sys.stderr)
    if caught is not None: raise caught
    return code or (3 if publication_failed else 0)


def validate_uri(uri):
    parsed = urlsplit(uri)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Use an HTTP(S) tracking URI without embedded credentials")


def sdk_client(uri):
    validate_uri(uri)
    from mlflow import MlflowClient
    if version("mlflow-skinny") != "3.17.1": raise ValueError("Install the pinned tracking extra")
    os.environ.setdefault("MLFLOW_HTTP_REQUEST_TIMEOUT", "10")
    os.environ.setdefault("MLFLOW_HTTP_REQUEST_MAX_RETRIES", "1")
    # MLflow's decorative run URL message is not encodable in redirected Windows
    # consoles. Our ASCII run-ID acknowledgment below is sufficient.
    os.environ.setdefault("MLFLOW_SUPPRESS_PRINTING_URL_TO_STDOUT", "true")
    return MlflowClient(tracking_uri=uri)


def publish(bundle, uri, client=None):
    validate_uri(uri)
    bundle = Path(bundle).resolve()
    with lock(bundle):
        state = json.loads((bundle / "state.json").read_bytes())
        destination = hashlib.sha256(uri.encode()).hexdigest()
        if state.get("destinationSha256", destination) != destination:
            raise ValueError("Bundle already bound to another destination")
        if state["status"] == "computing": raise ValueError("Computation incomplete; do not publish")
        if digest(bundle / "manifest.json") != state["manifestSha256"]: raise ValueError("Manifest changed")
        payload = json.loads((bundle / "manifest.json").read_bytes())
        local = json.loads((bundle / "local.json").read_bytes())
        if public_bindings(local["outputs"]) != payload["outputs"]:
            raise ValueError("Local output bindings changed")
        for record in local["outputs"]:
            if record["sha256"] is not None and digest(record["path"]) != record["sha256"]:
                raise ValueError("Output changed; publication rejected")
        if state["status"] == "published": return state["attempts"][-1]["runId"]
        operation = payload["specification"]["operation"]
        if operation not in OPERATIONS: raise ValueError("Invalid operation")
        attempt = {"publicationId": uuid.uuid4().hex, "status": "publishing", "runId": None}
        previous = next((item["runId"] for item in reversed(state["attempts"]) if item.get("runId")), None)
        state["attempts"].append(attempt); state["status"] = "unpublished"
        state["destinationSha256"] = destination
        save(bundle / "state.json", state)
        try:
            client = client or sdk_client(uri)
            name = "pymk." + operation + ".v1"
            experiment = client.get_experiment_by_name(name)
            if experiment is None:
                try: experiment_id = client.create_experiment(name)
                except Exception:
                    experiment = client.get_experiment_by_name(name)
                    if experiment is None: raise
                    experiment_id = experiment.experiment_id
            else: experiment_id = experiment.experiment_id
            tags = {"pymk.logicalWorkId": payload["logicalWorkId"], "pymk.computationAttempt": payload["computationAttempt"],
                    "pymk.publicationId": attempt["publicationId"], "pymk.publication": "incomplete",
                    "pymk.outcome": payload["outcome"], "pymk.sourceSha256": payload["specification"]["build"]["sourceSha256"],
                    "pymk.qualityGate": payload["evidence"]["qualityGate"], "pymk.automaticPromotion": "false"}
            if previous: tags["pymk.retryOf"] = previous
            run_id = client.create_run(experiment_id, tags=tags).info.run_id
            attempt["runId"] = run_id; save(bundle / "state.json", state)
            for key, value in payload["specification"]["parameters"].items(): client.log_param(run_id, key, value, synchronous=True)
            for key, value in payload["evidence"].get("parameters", {}).items(): client.log_param(run_id, key, value, synchronous=True)
            if hasattr(client, "log_batch"):
                from mlflow.entities import Metric
                timestamp = int(time.time() * 1000)
                metrics = [Metric(key, value, timestamp, 0) for key, value in payload["evidence"]["metrics"].items()]
                for start in range(0, len(metrics), 100): client.log_batch(run_id, metrics=metrics[start:start+100], synchronous=True)
            else:
                for key, value in payload["evidence"]["metrics"].items(): client.log_metric(run_id, key, value, synchronous=True)
            client.log_artifact(run_id, str(bundle / "manifest.json"), "tracking")
            with tempfile.TemporaryDirectory() as directory:
                downloaded = client.download_artifacts(run_id, "tracking/manifest.json", directory)
                if digest(downloaded) != state["manifestSha256"]: raise ValueError("Tracking manifest checksum mismatch")
            client.set_terminated(run_id, status="FINISHED" if payload["outcome"] == "succeeded" else "FAILED")
            client.set_tag(run_id, "pymk.publication", "complete", synchronous=True)
            attempt["status"] = "acknowledged"
            state["status"] = "published"; state["destinationSha256"] = hashlib.sha256(uri.encode()).hexdigest()
            save(bundle / "state.json", state)
            print(f"Tracking published: {run_id} ({payload['outcome']})")
            return run_id
        except BaseException as error:
            state["status"] = "unpublished"
            attempt["status"] = "unconfirmed"; attempt["failureClass"] = type(error).__name__
            if attempt["runId"] and client is not None:
                try:
                    client.set_tag(attempt["runId"], "pymk.publication", "incomplete", synchronous=True)
                    client.set_terminated(attempt["runId"], status="FAILED")
                except Exception: pass
            save(bundle / "state.json", state)
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description="Publish a completed local tracking bundle without recomputing outputs")
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--tracking-uri", required=True)
    args = parser.parse_args(argv)
    try: publish(args.bundle, args.tracking_uri)
    except Exception:
        print("Publication not confirmed; inspect local bundle state, connectivity and credentials.", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
