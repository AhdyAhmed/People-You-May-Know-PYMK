"""Real server + packaged production Java acceptance, scoped to a disposable server."""
import argparse
from datetime import datetime, timezone, timedelta
from pathlib import Path
import subprocess
from unittest.mock import patch

import test_heavy_ranker_training as fixture_module
from test_registry import train_tracked
from pymk_training import registry, tracking, evaluate_heavy_ranker, tune_blend, profile_embeddings, release_manifest, model_sync
from pymk_training.blend_context_fixture import create


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--uri", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()
    root = args.root.resolve(); client = tracking.sdk_client(args.uri)
    fixture = fixture_module.HeavyRankerTrainingTest(); fixture.setUp()
    try:
        light = train_tracked(fixture, client, args.uri)
        heavy = train_tracked(fixture, client, args.uri, heavy=True)
        common = sum((["--input", str(path)] for path in fixture.paths), []) + [
            "--model", str(fixture.root / "tracked-heavy/heavy-registry.onnx"),
            "--metadata", str(fixture.root / "tracked-heavy/heavy-registry.metadata.json"),
            "--l1-model", str(fixture.l1), "--l1-metadata", str(fixture.l1_metadata)]
        policy = registry.read(Path(__file__).parents[1] / "evaluation-policy.json")
        policy.update(k=1, l1Survivors=1, bootstrapSamples=100)
        registry.immutable_json(root / "evaluation-policy.json", policy)
        assert evaluate_heavy_ranker.main(common + ["--policy", str(root / "evaluation-policy.json"),
            "--output", str(root / "evaluation.json"), "--tracking-dir", str(root / "eval-spool")]) == 0
        evaluation = next((root / "eval-spool").glob("*/*/manifest.json")).parent
        tracking.publish(evaluation, args.uri, client)
        registry.immutable_json(root / "blend-policy.json", {"schemaVersion": 1, "objective": "accepted-hits-per-requested-slot",
            "seed": 42, "searchBudget": 6, "k": 1, "l1Survivors": 3, "employerCapFraction": .4})
        create(fixture.paths, Path(__file__).parent / "fixtures/profiles.csv", root / "context.json")
        assert tune_blend.main(common + ["--policy", str(root / "blend-policy.json"), "--context", str(root / "context.json"),
            "--output-dir", str(root / "blend"), "--tracking-dir", str(root / "blend-spool")]) == 0
        blend = next((root / "blend-spool").glob("*/*/manifest.json")).parent
        tracking.publish(blend, args.uri, client)
        stages = [root / "light", root / "heavy"]
        for bundle, stage in zip((light, heavy), stages):
            registry.prepare(bundle, stage, args.jar, [evaluation, blend])
            assert registry.read(stage / "java-validation.json")["rows"] == 64
        print("PASS: exact L1/L2 ONNX bytes validated with production Java batch/singleton scoring; transform/dataset/evaluation/blend lineage bound")
        original_upload = client.log_artifact
        def interrupted(run_id, path, prefix):
            original_upload(run_id, path, prefix)
            if prefix.startswith("candidate/"): raise ConnectionError("simulated lost upload acknowledgment")
        with patch.object(client, "log_artifact", side_effect=interrupted):
            try: registry.publish(stages[0], args.uri, client)
            except ConnectionError: pass
            else: raise AssertionError("interrupted upload reported success")
        assert not list(client.search_model_versions("name = 'pymk.light.l1'"))
        assert registry.read(stages[0] / "registry-state.json")["status"] == "unpublished"
        for stage in stages:
            first = registry.publish(stage, args.uri, client)
            assert registry.publish(stage, args.uri, client) == first
        print("PASS: interrupted upload created no version; retries registered exact versions and acknowledged repeats were no-ops")
        vectors, release = root / "vectors.csv", root / "legacy-release.json"
        profile_embeddings.main(["--input", str(Path(__file__).parent / "fixtures/profiles.csv"),
            "--artifact", str(stages[0] / "transform.json"), "--output", str(vectors), "--as-of", "2026-02-01T00:00:00Z"])
        release_manifest.main(["--release-id", "registry-candidate", "--transform", str(stages[0] / "transform.json"),
            "--vectors", str(vectors), "--output", str(release)])
        before = release.read_bytes()
        extended = registry.extend_release(release, stages, root / "release-v2.json")
        assert release.read_bytes() == before
        assert extended["candidateLimits"] == registry.read(release)["candidateLimits"]
        registration = registry.read(stages[0] / "registered.json")
        client.set_registered_model_alias(registration["name"], "candidate", registration["version"])
        # Simulate an external actor bypassing the adapter and moving discovery
        # to genuinely different bytes (even the wrong model kind).
        other = registry.read(stages[1] / "registered.json")
        alternate = client.create_model_version(registration["name"], other["sourceUri"], run_id=other["runId"], await_creation_for=60)
        client.set_registered_model_alias(registration["name"], "candidate", alternate.version)
        assert client.get_model_version_by_alias(registration["name"], "candidate").source == other["sourceUri"]
        assert registry.publish(stages[0], args.uri, client) == registration
        assert registry.read(root / "release-v2.json")["registry"]["models"]["light"]["version"] == registration["version"]
        assert release.read_bytes() == before
        print("PASS: schema-2 extension preserved legacy compatibility; alias movement did not change pinned candidates or legacy release")
        synced = root / "synchronized"
        model_sync.sync(root / "release-v2.json", synced, args.uri, args.jar, client)
        assert (synced / "light/model.onnx").read_bytes() == (stages[0] / "model.onnx").read_bytes()
        assert (synced / "heavy/model.onnx").read_bytes() == (stages[1] / "model.onnx").read_bytes()
        assert registry.read(synced / "release.json")["registry"] == extended["registry"]
        assert not (synced / "approval.json").exists()
        print("PASS: Day 39 exact-version synchronization reran production Java parity and preserved pins after alias movement; no implicit approval")
        # The tiny demo cohort is deliberately NOT production approval evidence.
        now = datetime.now(timezone.utc)
        evidence = {"schemaVersion": 1, "manifestSha256": tracking.digest(synced / "release.json"),
            "measuredAt": now.isoformat(), "environment": "disposable tiny-cohort rejection probe",
            "cohortSha256": tracking.digest(root / "evaluation.json"), "mode": "L1",
            "coverage": {"observed": 1, "minimum": 1},
            "quality": {"observed": .5, "minimum": .5},
            "requesters": {"observed": 4, "minimum": 30}}
        registry.immutable_json(synced / "tiny-cohort-evidence.json", evidence)
        rejected = subprocess.run(["java", "-Dloader.main=com.ahdyahmed.pymk.batch.PromotionApproval", "-cp", str(args.jar),
            "org.springframework.boot.loader.launch.PropertiesLauncher", str(synced), str(synced / "tiny-cohort-evidence.json"),
            "smoke-operator", "0", (now + timedelta(hours=1)).isoformat()], capture_output=True, text=True, timeout=120)
        assert rejected.returncode != 0 and "Failed requesters gate" in rejected.stderr, rejected.stderr
        assert not (synced / "approval.json").exists()
        print("PASS: Day 39 packaged approval command rejected tiny-cohort evidence without creating approval")
        # Real server tampering after registration must be detected by read-back.
        corrupt = root / "corrupt"; corrupt.mkdir(); (corrupt / "model.onnx").write_bytes(b"corrupt")
        client.log_artifact(registration["runId"], str(corrupt / "model.onnx"), "candidate/" + registration["candidateSha256"])
        try: registry.publish(stages[0], args.uri, client)
        except ValueError: pass
        else: raise AssertionError("remote corruption accepted")
        try: model_sync.sync(root / "release-v2.json", root / "rejected-sync", args.uri, args.jar, client)
        except ValueError: pass
        else: raise AssertionError("corrupted artifact synchronized")
        assert not (root / "rejected-sync/sync.json").exists()
        print("PASS: changed registered artifact rejected; no promotion API was invoked")
    finally:
        fixture.doCleanups()


if __name__ == "__main__": main()
