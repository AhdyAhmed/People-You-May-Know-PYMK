"""Stage and register exact ONNX bundles. Registration never activates serving."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

import numpy as np
import onnx
import onnxruntime as ort

from . import tracking
from .dataset import FEATURE_NAMES
from .profile_embeddings import ProfileEmbeddingTransform, immutable_output


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_bytes())


def sha(value):
    return hashlib.sha256(tracking.canonical(value)).hexdigest()


def immutable_json(path, value):
    with immutable_output(Path(path)) as stream:
        json.dump(value, stream, sort_keys=True, indent=2, allow_nan=False)


def tracked_bundle(path, operations):
    path = Path(path)
    state, payload, local = (read(path / name) for name in ("state.json", "manifest.json", "local.json"))
    require(state["status"] == "published" and state["manifestSha256"] == tracking.digest(path / "manifest.json"),
            "Tracking evidence must be acknowledged and unchanged")
    require(payload["specification"]["operation"] in operations and payload["outcome"] != "failed", "Invalid source computation")
    require(tracking.public_bindings(local["outputs"]) == payload["outputs"], "Changed local output bindings")
    require(tracking.public_bindings(local["inputs"]) == payload["specification"]["inputs"], "Changed local input bindings")
    for item in local["outputs"]:
        require(item["sha256"] is not None and tracking.digest(item["path"]) == item["sha256"], "Changed tracked output")
    source = {"runId": state["attempts"][-1]["runId"], "manifestSha256": state["manifestSha256"],
              "logicalWorkId": payload["logicalWorkId"], "computationAttempt": payload["computationAttempt"],
              "destinationSha256": state["destinationSha256"]}
    return payload, local, source


def copy_binding(stage, name, record):
    content = Path(record["path"]).read_bytes()
    require(hashlib.sha256(content).hexdigest() == record["sha256"], "Input changed before staging")
    with (stage / name).open("xb") as stream:
        stream.write(content)


def embedded_tensors_only(message):
    """An exact model.onnx must not read unbound external tensor files."""
    if isinstance(message, onnx.TensorProto):
        require(message.data_location != onnx.TensorProto.EXTERNAL and not message.external_data,
                "External ONNX tensor data is not supported")
    for field, value in message.ListFields():
        if field.message_type is not None:
            for child in value if field.is_repeated else (value,):
                embedded_tensors_only(child)


def parity_fixture(stage, kind):
    metadata = read(stage / "metadata.json")
    artifact = (stage / "model.onnx").read_bytes()
    require(metadata["schemaVersion"] == 1 and metadata["features"] == list(FEATURE_NAMES), "Invalid metadata feature contract")
    require(metadata["onnx"]["sha256"] == hashlib.sha256(artifact).hexdigest(), "Model/metadata checksum mismatch")
    graph = onnx.load_model_from_string(artifact)
    embedded_tensors_only(graph)
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(artifact, providers=["CPUExecutionProvider"])
    require(len(session.get_inputs()) == 1 and session.get_inputs()[0].name == "features"
            and session.get_inputs()[0].type == "tensor(float)", "Invalid input tensor")
    features = np.random.RandomState(42).uniform(0, 1, (64, 6)).astype(np.float32)
    features[:, 0] *= 20; features[:, 5] *= 20
    features[:, 1:4] = (features[:, 1:4] > .5).astype(np.float32)
    features[:, 4] = 2 * features[:, 4] - 1
    features[0] = 0
    if kind == "light":
        require(metadata.get("positiveClass") == 1, "Invalid positive class")
        results = session.run(None, {"features": features})
        candidates = [item for item in results if isinstance(item, np.ndarray) and item.shape == (64, 2)]
        require(len(candidates) == 1, "Invalid L1 probability tensor")
        expected = candidates[0][:, 1:2]
        parameters = metadata["parameters"]
        logits = ((features - np.asarray(parameters["scalerMean"])) / np.asarray(parameters["scalerScale"])) @ np.asarray(parameters["coefficients"]) + parameters["intercept"]
        reference = 1 / (1 + np.exp(-np.clip(logits, -700, 700)))
        require(np.allclose(expected[:, 0], reference, atol=1e-6, rtol=0), "L1 learned-parameter parity failed")
    else:
        outputs = [head["name"] for head in metadata["outputTensors"]]
        require(len(outputs) == 2, "Invalid L2 heads")
        values = session.run(outputs, {"features": features})
        require(all(item.shape == (64, 1) for item in values), "Invalid L2 shape")
        expected = np.concatenate(values, axis=1)
        error = metadata["onnx"].get("maxParityAbsoluteError")
        require(type(error) in (int, float) and 0 <= error <= 1e-6, "Missing training parity evidence")
    require(np.isfinite(expected).all() and (expected >= 0).all() and (expected <= 1).all(), "Invalid probabilities")
    immutable_json(stage / "parity.json", {"kind": kind, "features": features.tolist(), "expected": expected.tolist()})


def prepare(bundle, stage, java_jar, lineage_bundles=()):
    payload, local, source = tracked_bundle(bundle, {"light", "heavy"})
    require(payload["outcome"] == "succeeded", "Failed training cannot be registered")
    kind = payload["specification"]["operation"]
    outputs = {item["role"]: item for item in local["outputs"]}
    inputs = {item["role"]: item for item in local["inputs"]}
    require("embedding-transform" in inputs, "Registry candidates require a frozen transform")
    stage = Path(stage).resolve(); stage.mkdir(parents=True, exist_ok=False)
    copy_binding(stage, "model.onnx", outputs["onnx"])
    copy_binding(stage, "metadata.json", outputs["report"])
    copy_binding(stage, "transform.json", inputs["embedding-transform"])
    metadata = read(stage / "metadata.json")
    compatibility = metadata["embeddingCompatibility"]
    transform = ProfileEmbeddingTransform.load(stage / "transform.json")
    require(compatibility["transformSha256"] == tracking.digest(stage / "transform.json")
            and compatibility["embeddingVersion"] == transform.metadata["embeddingVersion"]
            and compatibility["fitCutoff"] == transform.metadata["fitCutoff"]
            and compatibility["featureSchema"] == "pair-v1", "Incompatible transform")
    require(metadata["modelVersion"] != "legacy" and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", metadata["modelVersion"]), "Invalid model version")
    sources = [source]
    immutable_json(stage / "tracking.json", payload)
    immutable_json(stage / "dataset.json", {"inputs": payload["specification"]["inputs"], "evidence": payload["evidence"]})
    seen = set()
    for path in lineage_bundles:
        evidence, bindings, origin = tracked_bundle(path, {"evaluate", "blend"})
        operation = evidence["specification"]["operation"]
        require(operation not in seen and origin["destinationSha256"] == source["destinationSha256"], "Duplicate or foreign lineage")
        seen.add(operation)
        files = {item["role"]: item for item in bindings["outputs"]}
        report = read(files["report"]["path"])
        model_ref = report if kind == "heavy" else report["l1"]
        require(model_ref.get("modelSha256", model_ref.get("onnxSha256")) == outputs["onnx"]["sha256"]
                and model_ref["metadataSha256"] == outputs["report"]["sha256"], "Unrelated evaluation/blend lineage")
        copy_binding(stage, operation + ".json", files["report"])
        if operation == "blend":
            require(report["configurationSha256"] == files["configuration"]["sha256"], "Blend configuration mismatch")
            copy_binding(stage, "blend.properties", files["configuration"])
        immutable_json(stage / (operation + "-tracking.json"), evidence)
        sources.append(origin)
    parity_fixture(stage, kind)
    jar = Path(java_jar).resolve(); jar_hash = tracking.digest(jar)
    command = ["java", "-Dloader.main=com.ahdyahmed.pymk.batch.RegistryModelVerifier", "-cp", str(jar),
               "org.springframework.boot.loader.launch.PropertiesLauncher", str(stage), str(stage / "java-validation.json")]
    result = subprocess.run(command, capture_output=True, timeout=120, check=False)
    require(result.returncode == 0, "Production Java metadata/tensor/parity validation failed")
    require(tracking.digest(jar) == jar_hash, "Java verifier changed")
    validation = read(stage / "java-validation.json")
    require(validation["modelSha256"] == tracking.digest(stage / "model.onnx")
            and validation["metadataSha256"] == tracking.digest(stage / "metadata.json")
            and validation["fixtureSha256"] == tracking.digest(stage / "parity.json"), "Unbound Java evidence")
    inventory = {item.name: tracking.digest(item) for item in sorted(stage.iterdir()) if item.is_file()}
    candidate = {"schemaVersion": 1, "kind": kind, "modelVersion": metadata["modelVersion"],
                 "compatibility": compatibility, "features": metadata["features"], "sources": sources,
                 "files": inventory, "javaVerifierSha256": jar_hash, "automaticPromotion": False,
                 "l1": metadata.get("comparison", {}).get("l1")}
    immutable_json(stage / "candidate.json", candidate)
    tracking.save(stage / "registry-state.json", {"candidateSha256": sha(candidate), "status": "unpublished", "attempts": []})
    return candidate


def staged(stage):
    stage = Path(stage)
    candidate, state = read(stage / "candidate.json"), read(stage / "registry-state.json")
    require(sha(candidate) == state["candidateSha256"], "Changed candidate manifest")
    for name, expected in candidate["files"].items():
        require(re.fullmatch(r"[a-z-]+\.(json|onnx|properties)", name) and tracking.digest(stage / name) == expected,
                "Changed candidate artifact")
    return candidate, state


def remote_sources(client, candidate, uri):
    for source in candidate["sources"]:
        require(source["destinationSha256"] == hashlib.sha256(uri.encode()).hexdigest(), "Tracking destination changed")
        run = client.get_run(source["runId"])
        require(run.data.tags.get("pymk.publication") == "complete"
                and run.data.tags.get("pymk.logicalWorkId") == source["logicalWorkId"]
                and run.data.tags.get("pymk.computationAttempt") == source["computationAttempt"], "Unacknowledged tracking source")
        with tempfile.TemporaryDirectory() as directory:
            downloaded = client.download_artifacts(source["runId"], "tracking/manifest.json", directory)
            require(tracking.digest(downloaded) == source["manifestSha256"], "Remote tracking evidence changed")


def readback(client, run_id, prefix, candidate):
    with tempfile.TemporaryDirectory() as directory:
        for name, expected in candidate["files"].items():
            downloaded = client.download_artifacts(run_id, prefix + "/" + name, directory)
            require(tracking.digest(downloaded) == expected, "Remote artifact checksum mismatch")
        downloaded = client.download_artifacts(run_id, prefix + "/candidate.json", directory)
        require(sha(read(downloaded)) == sha(candidate), "Remote candidate changed")


def publish(stage, uri, client=None):
    tracking.validate_uri(uri)
    stage = Path(stage).resolve()
    with tracking.lock(stage):
        candidate, state = staged(stage)
        digest = sha(candidate)
        client = client or tracking.sdk_client(uri)
        remote_sources(client, candidate, uri)
        name = "pymk." + candidate["kind"] + "." + candidate["modelVersion"]
        identity = sha({key: candidate["files"][key] for key in ("model.onnx", "metadata.json")})
        try:
            claimed = client.create_registered_model(name, tags={"pymk.modelIdentity": identity})
        except Exception:
            claimed = client.get_registered_model(name)
        require(claimed.tags.get("pymk.modelIdentity") == identity, "Model version already identifies different bytes")
        if state["status"] == "published":
            registration = read(stage / "registered.json")
            require(sha(registration) == state["registrationSha256"], "Changed registration acknowledgment")
            version = client.get_model_version(name, registration["version"])
            require(version.run_id == registration["runId"] and version.source == registration["sourceUri"]
                    and version.status == "READY" and version.tags.get("pymk.candidateSha256") == digest,
                    "Pinned registry version changed")
            run = client.get_run(registration["runId"])
            require(run.info.status == "FINISHED" and run.data.tags.get("pymk.registration") == "complete",
                    "Registration acknowledgment changed")
            readback(client, registration["runId"], "candidate/" + digest, candidate)
            return registration
        attempt = {"id": uuid.uuid4().hex, "status": "uploading", "runId": None}
        state["attempts"].append(attempt)
        tracking.save(stage / "registry-state.json", state)
        try:
            experiment_name = "pymk.registry.v1"
            experiment = client.get_experiment_by_name(experiment_name)
            if experiment is None:
                try: experiment_id = client.create_experiment(experiment_name)
                except Exception:
                    experiment_id = client.get_experiment_by_name(experiment_name).experiment_id
            else: experiment_id = experiment.experiment_id
            run = client.create_run(experiment_id, tags={"pymk.candidateSha256": digest,
                    "pymk.registration": "incomplete", "pymk.attempt": attempt["id"], "pymk.automaticPromotion": "false"})
            run_id = attempt["runId"] = run.info.run_id
            tracking.save(stage / "registry-state.json", state)
            prefix = "candidate/" + digest
            # New upload attempt gets a fresh run/prefix; never overwrite a registered bundle.
            for file in list(candidate["files"]) + ["candidate.json"]:
                client.log_artifact(run_id, str(stage / file), prefix)
            staged(stage)
            readback(client, run_id, prefix, candidate)
            source_uri = client.get_run(run_id).info.artifact_uri.rstrip("/") + "/" + prefix
            version = client.create_model_version(name, source_uri, run_id=run_id,
                         tags={"pymk.candidateSha256": digest, "pymk.automaticPromotion": "false"}, await_creation_for=60)
            require(version.status == "READY" and version.source == source_uri and version.run_id == run_id
                    and version.name == name and re.fullmatch(r"[1-9][0-9]*", str(version.version))
                    and version.tags.get("pymk.candidateSha256") == digest, "Registry version not ready")
            registration = {"schemaVersion": 1, "name": name, "version": str(version.version), "runId": run_id,
                    "artifactUri": f"models:/{name}/{version.version}", "sourceUri": source_uri,
                    "candidateSha256": digest, "kind": candidate["kind"], "modelVersion": candidate["modelVersion"],
                    "files": candidate["files"], "compatibility": candidate["compatibility"], "features": candidate["features"],
                    "trackingRunIds": [source["runId"] for source in candidate["sources"]], "l1": candidate["l1"],
                    "automaticPromotion": False}
            client.set_terminated(run_id, status="FINISHED")
            client.set_tag(run_id, "pymk.registration", "complete", synchronous=True)
            # Atomic replacement is only for recovery from lost local acknowledgment;
            # consumers receive a separate immutable release-manifest copy below.
            tracking.save(stage / "registered.json", registration)
            attempt["status"] = "acknowledged"; attempt["version"] = str(version.version)
            state["status"] = "published"
            state["registrationSha256"] = sha(registration)
            tracking.save(stage / "registry-state.json", state)
            return registration
        except BaseException as error:
            attempt["status"] = "unconfirmed"; attempt["failureClass"] = type(error).__name__
            state["status"] = "unpublished"
            tracking.save(stage / "registry-state.json", state)
            if attempt["runId"]:
                try:
                    client.set_tag(attempt["runId"], "pymk.registration", "incomplete", synchronous=True)
                    client.set_terminated(attempt["runId"], status="FAILED")
                except Exception: pass
            raise


def extend_release(release, stages, output):
    document = read(release)
    require(document.get("schemaVersion") == 1 and "registry" not in document, "Extend a legacy schema-1 manifest; do not mutate a pinned release")
    bindings = {}
    for stage in stages:
        candidate, state = staged(stage)
        require(state["status"] == "published", "Unacknowledged registry candidate")
        registered = read(Path(stage) / "registered.json")
        require(sha(registered) == state["registrationSha256"], "Changed registration acknowledgment")
        require(registered["candidateSha256"] == sha(candidate) and registered["files"] == candidate["files"], "Changed registry bindings")
        require(registered["compatibility"]["transformSha256"] == document["transformSha256"], "Release/registry transform mismatch")
        require(registered["kind"] not in bindings, "Duplicate model kind")
        bindings[registered["kind"]] = registered
    require("light" in bindings, "Pinned release requires L1")
    if "heavy" in bindings:
        require(bindings["heavy"]["l1"]["onnxSha256"] == bindings["light"]["files"]["model.onnx"]
                and bindings["heavy"]["l1"]["metadataSha256"] == bindings["light"]["files"]["metadata.json"], "L2/L1 identity mismatch")
    # Moving the manifest must not change the meaning of its existing relative paths.
    for key in ("vectorsPath", "transformPath"):
        document[key] = str((Path(release).resolve().parent / document[key]).resolve())
        require(tracking.digest(document[key]) == document[key.replace("Path", "Sha256")], "Changed release artifact")
    document["schemaVersion"] = 2
    document["registry"] = {"schemaVersion": 1, "models": bindings, "automaticPromotion": False}
    immutable_json(output, document)
    return document


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--bundle", required=True, type=Path)
    prepare_parser.add_argument("--output-dir", required=True, type=Path)
    prepare_parser.add_argument("--java-verifier-jar", required=True, type=Path)
    prepare_parser.add_argument("--lineage-bundle", action="append", default=[], type=Path)
    publish_parser = commands.add_parser("publish")
    publish_parser.add_argument("--stage", required=True, type=Path)
    publish_parser.add_argument("--tracking-uri", required=True)
    extend_parser = commands.add_parser("extend-release")
    extend_parser.add_argument("--release", required=True, type=Path)
    extend_parser.add_argument("--stage", required=True, action="append", type=Path)
    extend_parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    if args.operation == "prepare":
        prepare(args.bundle, args.output_dir, args.java_verifier_jar, args.lineage_bundle)
    elif args.operation == "publish":
        registration = publish(args.stage, args.tracking_uri)
        print("Registered pinned candidate: " + registration["artifactUri"] + " (not activated)")
    else: extend_release(args.release, args.stage, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
