"""Disposable real-server lifecycle rehearsal; never approves or activates a model."""
import argparse
from pathlib import Path
import re
import subprocess

import day38_registry_probe
from pymk_training import model_sync, registry, tracking, train_light_ranker


def failed_sync(root, uri, jar, name):
    output = root / name
    try:
        model_sync.sync(root / "release-v2.json", output, uri, jar)
    except Exception as error:
        assert not (output / "sync.json").exists(), "Failure published a completion record"
        print(f"PASS: {name} rejected ({type(error).__name__}); no completion record")
    else:
        raise AssertionError(f"{name} unexpectedly synchronized")


def backup_inventory(root):
    files = [root / "metadata.dump", *sorted((root / "artifacts").rglob("*"))]
    assert files[0].is_file() and files[0].stat().st_size > 0
    inventory = {p.relative_to(root).as_posix(): tracking.digest(p) for p in files if p.is_file()}
    assert len(inventory) > 1, "Empty artifact backup"
    return inventory


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["seed", "outage", "artifact-outage", "backup", "verify-backup", "restored"])
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--uri", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--container")
    args = parser.parse_args(argv)
    root, uri, jar = args.root.resolve(), args.uri, args.jar.resolve()
    if args.phase == "seed":
        day38_registry_probe.main(["--root", str(root), "--uri", uri, "--jar", str(jar), "--retain-registry"])
        frozen = root / "frozen-inputs"
        replay = root / "frozen-replay"
        assert train_light_ranker.main(sum((["--input", str(frozen / f"l1-{i}.csv")] for i in range(3)), []) + [
            "--embedding-transform", str(frozen / "transform.json"), "--model-version", "day40-replay",
            "--output-dir", str(replay), "--tracking-dir", str(root / "replay-tracking")]) == 0
        repeated = registry.read(replay / "day40-replay.metadata.json")
        original = registry.read(root / "light/metadata.json")
        assert repeated["metrics"] == original["metrics"]
        assert repeated["parameters"] == original["parameters"]
        print("PASS: documented frozen-data replay produced identical learned parameters/metrics and a new offline tracking bundle")
        client = tracking.sdk_client(uri)
        counts = {kind: len(client.search_model_versions(f"name = '{registry.read(root / kind / 'registered.json')['name']}'"))
                  for kind in ("light", "heavy")}
        registry.immutable_json(root / "version-counts.json", counts)
        print("PASS: frozen-fixture training, tracking, comparison, registration and guarded sync demo; quality failure is NOT approval")
    elif args.phase == "outage":
        failed_sync(root, uri, jar, "tracking-outage")
        before = (root / "light/registered.json").read_bytes()
        try:
            registry.publish(root / "light", uri)
        except Exception:
            assert (root / "light/registered.json").read_bytes() == before
        else:
            raise AssertionError("Offline registry publication unexpectedly succeeded")
        print("PASS: registry outage did not rewrite the acknowledged candidate")
    elif args.phase == "artifact-outage":
        assert args.container and re.fullmatch(r"[a-f0-9]{12,64}", args.container)
        registration = registry.read(root / "light/registered.json")
        source = registration["sourceUri"]
        assert re.fullmatch(r"mlflow-artifacts:/[0-9]+/[a-f0-9]{32}/artifacts/candidate/[a-f0-9]{64}", source)
        path = "/mlartifacts/" + source.removeprefix("mlflow-artifacts:/") + "/model.onnx"
        # The non-root server owns this one test artifact. No global volume permission changes.
        subprocess.run(["docker", "exec", args.container, "chmod", "000", path], check=True, timeout=30)
        try:
            tracking.sdk_client(uri).get_model_version(registration["name"], registration["version"])
            failed_sync(root, uri, jar, "artifact-outage")
        finally:
            subprocess.run(["docker", "exec", args.container, "chmod", "644", path], check=True, timeout=30)
        model_sync.sync(root / "release-v2.json", root / "artifact-recovered", uri, jar)
        print("PASS: tracking remained available during artifact outage; fresh-directory retry recovered")
    elif args.phase == "backup":
        registry.immutable_json(root / "backup-manifest.json", {"schemaVersion": 1, "trackingUri": uri,
            "retention": "retain-all; no registry/artifact garbage collection", "files": backup_inventory(root)})
        print("PASS: coordinated backup sealed with exact metadata/artifact inventory")
    elif args.phase == "verify-backup":
        backup = registry.read(root / "backup-manifest.json")
        assert backup["trackingUri"] == uri, "Restore must retain the original tracking identity"
        assert backup["files"] == backup_inventory(root), "Backup bytes or inventory changed"
        print("PASS: backup inventory verified before restore")
    else:
        client = tracking.sdk_client(uri)
        model_sync.sync(root / "release-v2.json", root / "restored-sync", uri, jar, client)
        assert registry.read(root / "restored-sync/release.json")["registry"] == registry.read(root / "release-v2.json")["registry"]
        counts = registry.read(root / "version-counts.json")
        for kind in ("light", "heavy"):
            before = registry.read(root / kind / "registered.json")
            assert registry.publish(root / kind, uri, client) == before
            assert len(client.search_model_versions(f"name = '{before['name']}'")) == counts[kind]
            for name, digest in before["files"].items():
                assert tracking.digest(root / "restored-sync" / kind / name) == digest
        assert not (root / "restored-sync/approval.json").exists()
        print("PASS: fresh-volume restore reproduced exact pinned L1/L2, evaluation/blend lineage and original run/version IDs; retry created no versions")
        registration = registry.read(root / "light/registered.json")
        corrupt = root / "corrupt"; corrupt.mkdir(); (corrupt / "model.onnx").write_bytes(b"corrupt")
        client.log_artifact(registration["runId"], str(corrupt / "model.onnx"), "candidate/" + registration["candidateSha256"])
        failed_sync(root, uri, jar, "corrupt-restored-download")
        print("PASS: corrupted restored artifact cannot become a synchronized candidate")


if __name__ == "__main__":
    main()
