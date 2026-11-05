"""Download pinned registry artifacts for offline approval; never activate serving."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from . import registry as r, tracking


def sync(release, output, uri, java_jar, client=None):
    tracking.validate_uri(uri)
    release = Path(release).resolve()
    source_bytes = release.read_bytes()
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    document = json.loads(source_bytes)
    r.require(document.get("schemaVersion") == 2, "Pinned schema-2 release required")
    models = document["registry"]["models"]
    r.require("light" in models and set(models) <= {"light", "heavy"}, "Invalid model roles")
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    client = client or tracking.sdk_client(uri)
    verifier_hash = tracking.digest(java_jar)
    # Incomplete directories have no sync.json and cannot be approved; retry in a fresh directory.
    inventory = {}
    for kind, reference in models.items():
        r.require(re.fullmatch(r"[1-9][0-9]*", reference["version"]), "Numeric version required")
        version = client.get_model_version(reference["name"], reference["version"])
        r.require(version.status == "READY" and version.run_id == reference["runId"]
                  and version.source == reference["sourceUri"]
                  and version.tags.get("pymk.candidateSha256") == reference["candidateSha256"], "Pinned version changed")
        run = client.get_run(reference["runId"])
        r.require(run.info.status == "FINISHED" and run.data.tags.get("pymk.registration") == "complete", "Unacknowledged registration")
        prefix = "candidate/" + reference["candidateSha256"]
        stage = output / kind
        stage.mkdir()
        with tempfile.TemporaryDirectory() as temporary:
            candidate = r.read(client.download_artifacts(reference["runId"], prefix + "/candidate.json", temporary))
            r.require(r.sha(candidate) == reference["candidateSha256"] and candidate["files"] == reference["files"]
                      and candidate["kind"] == kind and candidate["modelVersion"] == reference["modelVersion"]
                      and candidate["compatibility"] == reference["compatibility"]
                      and candidate["features"] == reference["features"] and candidate["l1"] == reference["l1"]
                      and [source["runId"] for source in candidate["sources"]] == reference["trackingRunIds"], "Candidate binding changed")
            r.remote_sources(client, candidate, uri)
            for name, digest in candidate["files"].items():
                r.require(re.fullmatch(r"[a-z-]+\.(json|onnx|properties)", name), "Unsafe artifact name")
                downloaded = client.download_artifacts(reference["runId"], prefix + "/" + name, temporary)
                r.copy_binding(stage, name, {"path": downloaded, "sha256": digest})
                inventory[kind + "/" + name] = digest
        # Re-run the production loader/parity gate, do not trust remote success alone.
        command = ["java", "-Dloader.main=com.ahdyahmed.pymk.batch.RegistryModelVerifier", "-cp", str(Path(java_jar).resolve()),
                   "org.springframework.boot.loader.launch.PropertiesLauncher", str(stage), str(stage / "sync-validation.json")]
        result = subprocess.run(command, capture_output=True, timeout=120, check=False)
        r.require(result.returncode == 0, "Downloaded model failed Java validation")
        inventory[kind + "/sync-validation.json"] = tracking.digest(stage / "sync-validation.json")
    for name in ("vectors", "transform"):
        source = (release.parent / document[name + "Path"]).resolve()
        target = name + (".csv" if name == "vectors" else ".json")
        r.copy_binding(output, target, {"path": source, "sha256": document[name + "Sha256"]})
        document[name + "Path"] = str(output / target)
        inventory[target] = document[name + "Sha256"]
    r.immutable_json(output / "release.json", document)
    inventory["release.json"] = tracking.digest(output / "release.json")
    r.require(tracking.digest(java_jar) == verifier_hash, "Java verifier changed during synchronization")
    r.require(tracking.digest(release) == source_hash, "Source manifest changed during synchronization")
    record = {"schemaVersion": 1, "sourceManifestSha256": source_hash, "files": inventory,
              "javaVerifierSha256": verifier_hash, "automaticPromotion": False}
    r.immutable_json(output / "sync.json", record)
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--tracking-uri", required=True)
    parser.add_argument("--java-verifier-jar", required=True, type=Path)
    args = parser.parse_args(argv)
    sync(args.release, args.output_dir, args.tracking_uri, args.java_verifier_jar)
    print("Synchronized pinned candidate; explicit approval and activation still required")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
