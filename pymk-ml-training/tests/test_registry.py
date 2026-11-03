import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid
import onnx
import tempfile
from test_light_ranker_training import _write_snapshots

import test_heavy_ranker_training as training_fixture
from pymk_training import registry, tracking, train_light_ranker, train_heavy_ranker


class RegistryClient:
    def __init__(self):
        self.runs = {}; self.models = {}; self.versions = {}; self.aliases = {}
        self.fail_upload = False; self.corrupt = False; self.fail_creation_ack = False
    def get_experiment_by_name(self, name): return SimpleNamespace(experiment_id="1")
    def create_run(self, experiment_id, tags):
        run_id = uuid.uuid4().hex
        self.runs[run_id] = {"tags": dict(tags), "metrics": {}, "params": {}, "files": {}, "status": "RUNNING"}
        return self.get_run(run_id)
    def get_run(self, run_id):
        run = self.runs[run_id]
        return SimpleNamespace(info=SimpleNamespace(run_id=run_id, artifact_uri="mlflow-artifacts:/" + run_id, status=run["status"]),
                               data=SimpleNamespace(tags=run["tags"], metrics=run["metrics"], params=run["params"]))
    def log_param(self, run, key, value, **kwargs): self.runs[run]["params"][key] = str(value)
    def log_metric(self, run, key, value, **kwargs): self.runs[run]["metrics"][key] = value
    def set_tag(self, run, key, value, *, synchronous):
        assert synchronous
        self.runs[run]["tags"][key] = value
    def set_terminated(self, run, status): self.runs[run]["status"] = status
    def log_artifact(self, run, path, prefix):
        self.runs[run]["files"][prefix + "/" + Path(path).name] = Path(path).read_bytes()
        if self.fail_upload and prefix.startswith("candidate/"): raise ConnectionError("interrupted upload")
    def download_artifacts(self, run, path, folder):
        output = Path(folder) / Path(path).name
        content = self.runs[run]["files"][path]
        if self.corrupt and path.startswith("candidate/"): content = b"corrupt"
        output.write_bytes(content)
        return str(output)
    def create_registered_model(self, name, tags):
        if name in self.models: raise ValueError("already exists")
        self.models[name] = SimpleNamespace(tags=dict(tags))
        return self.models[name]
    def get_registered_model(self, name): return self.models[name]
    def create_model_version(self, name, source, run_id, tags, **kwargs):
        version = str(1 + len([key for key in self.versions if key[0] == name]))
        value = SimpleNamespace(name=name, version=version, source=source, run_id=run_id, tags=tags, status="READY")
        self.versions[name, version] = value
        if self.fail_creation_ack: raise ConnectionError("lost registration acknowledgment")
        return value
    def get_model_version(self, name, version): return self.versions[name, version]


def train_tracked(fixture, client, uri, heavy=False):
    root = fixture.root
    if heavy:
        paths = fixture.paths
        operation, version, directory = "heavy", "heavy-registry", root / "tracked-heavy"
        args = ["--l1-model", str(fixture.l1), "--l1-metadata", str(fixture.l1_metadata), "--epochs", "12"]
        command = train_heavy_ranker.main
    else:
        paths = sorted(root.glob("l1-*.csv"))
        operation, version, directory, args = "light", "l1", root / "tracked-l1", []
        command = train_light_ranker.main
    args += sum((["--input", str(path)] for path in paths), [])
    args += ["--output-dir", str(directory), "--model-version", version, "--embedding-transform", str(fixture.transform),
             "--tracking-dir", str(root / (operation + "-spool"))]
    assert command(args) == 0
    bundle = next((root / (operation + "-spool")).glob("*/*/manifest.json")).parent
    tracking.publish(bundle, uri, client)
    if not heavy:
        fixture.l1, fixture.l1_metadata = directory / "l1.onnx", directory / "l1.metadata.json"
    return bundle


def java_evidence(command, **kwargs):
    """Unit stub only; live smoke runs the actual packaged Java verifier."""
    stage, output = Path(command[-2]), Path(command[-1])
    registry.immutable_json(output, {"modelSha256": tracking.digest(stage / "model.onnx"),
        "metadataSha256": tracking.digest(stage / "metadata.json"), "fixtureSha256": tracking.digest(stage / "parity.json")})
    return SimpleNamespace(returncode=0)


class EmbeddedOnnxTest(unittest.TestCase):
    def test_external_weights_in_nested_graph_are_rejected(self):
        tensor = onnx.TensorProto(name="weights", data_type=onnx.TensorProto.FLOAT, data_location=onnx.TensorProto.EXTERNAL)
        tensor.external_data.add(key="location", value="unbound.bin")
        nested = onnx.helper.make_graph([], "nested", [], [], [tensor])
        model = onnx.helper.make_model(onnx.helper.make_graph([onnx.helper.make_node("If", ["condition"], ["out"], then_branch=nested)], "root", [], []))
        with self.assertRaisesRegex(ValueError, "External ONNX"): registry.embedded_tensors_only(model)
    def test_nonconstant_l1_parameters_match_onnx_on_registry_probe(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            model, metadata, _ = train_light_ranker.train_and_export(_write_snapshots(root / "data", 8), root / "trained", "nonconstant")
            stage = root / "stage"; stage.mkdir()
            (stage / "model.onnx").write_bytes(model.read_bytes())
            (stage / "metadata.json").write_bytes(metadata.read_bytes())
            registry.parity_fixture(stage, "light")
            probabilities = [row[0] for row in registry.read(stage / "parity.json")["expected"]]
            self.assertGreater(max(probabilities) - min(probabilities), .1)


class RegistryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = training_fixture.HeavyRankerTrainingTest(); self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root; self.client = RegistryClient(); self.uri = "http://localhost:5000"
        self.bundle = train_tracked(self.fixture, self.client, self.uri)
        self.jar = self.root / "verifier.jar"; self.jar.write_bytes(b"test verifier identity")
        self.stage = self.root / "stage"
    def prepare(self, stage=None, bundle=None):
        with patch.object(registry.subprocess, "run", side_effect=java_evidence):
            return registry.prepare(bundle or self.bundle, stage or self.stage, self.jar)
    def test_exact_bytes_registered_and_acknowledged_retry_is_noop(self):
        candidate = self.prepare()
        registered = registry.publish(self.stage, self.uri, self.client)
        self.assertEqual(registered["files"]["model.onnx"], tracking.digest(self.fixture.l1))
        self.assertEqual(registry.publish(self.stage, self.uri, self.client), registered)
        self.assertEqual(len(self.client.versions), 1)
        self.assertFalse(candidate["automaticPromotion"])
        self.assertEqual(set(candidate["features"]), set(registry.FEATURE_NAMES))
    def test_partial_upload_does_not_register_and_retry_keeps_original_bytes(self):
        self.prepare(); before = (self.stage / "model.onnx").read_bytes()
        self.client.fail_upload = True
        with self.assertRaises(ConnectionError): registry.publish(self.stage, self.uri, self.client)
        self.assertEqual(self.client.versions, {})
        self.assertEqual(registry.read(self.stage / "registry-state.json")["status"], "unpublished")
        self.client.fail_upload = False
        registry.publish(self.stage, self.uri, self.client)
        self.assertEqual(before, (self.stage / "model.onnx").read_bytes())
    def test_corrupted_download_never_creates_version(self):
        self.prepare(); self.client.corrupt = True
        with self.assertRaises(ValueError): registry.publish(self.stage, self.uri, self.client)
        self.assertFalse(self.client.versions)
    def test_mutated_staged_file_rejected_before_registry_claim(self):
        self.prepare(); (self.stage / "model.onnx").write_bytes(b"changed")
        with self.assertRaises(ValueError): registry.publish(self.stage, self.uri, self.client)
        self.assertFalse(self.client.models)
    def test_reused_output_directory_rejected(self):
        self.prepare()
        with self.assertRaises(FileExistsError): self.prepare()
    def test_reused_model_version_with_different_bytes_rejected(self):
        self.prepare(); self.client.models["pymk.light.l1"] = SimpleNamespace(tags={"pymk.modelIdentity": "different"})
        with self.assertRaisesRegex(ValueError, "different bytes"): registry.publish(self.stage, self.uri, self.client)
        self.assertFalse(self.client.versions)
    def test_java_parity_failure_cannot_be_published(self):
        with patch.object(registry.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(ValueError, "Java"): registry.prepare(self.bundle, self.stage, self.jar)
        self.assertFalse((self.stage / "candidate.json").exists())
    def test_wrong_tracking_destination_rejected(self):
        self.prepare()
        with self.assertRaisesRegex(ValueError, "destination"): registry.publish(self.stage, "http://other:5000", self.client)
        self.assertFalse(self.client.models)
    def test_lost_registration_ack_is_unpublished_and_retry_has_exact_distinct_version(self):
        self.prepare(); self.client.fail_creation_ack = True
        with self.assertRaises(ConnectionError): registry.publish(self.stage, self.uri, self.client)
        self.assertEqual(registry.read(self.stage / "registry-state.json")["status"], "unpublished")
        self.client.fail_creation_ack = False
        result = registry.publish(self.stage, self.uri, self.client)
        self.assertEqual(result["version"], "2")  # No false exactly-once claim.
    def test_alias_movement_cannot_change_pinned_registration(self):
        self.prepare(); registered = registry.publish(self.stage, self.uri, self.client)
        self.client.aliases["champion"] = "999"
        self.assertEqual(registry.publish(self.stage, self.uri, self.client), registered)
    def test_changed_registration_ack_rejected(self):
        self.prepare(); registry.publish(self.stage, self.uri, self.client)
        registration = registry.read(self.stage / "registered.json"); registration["version"] = "9"
        tracking.save(self.stage / "registered.json", registration)
        with self.assertRaises(ValueError): registry.publish(self.stage, self.uri, self.client)
    def test_heavy_candidate_preserves_l1_binding(self):
        heavy = train_tracked(self.fixture, self.client, self.uri, heavy=True)
        candidate = self.prepare(bundle=heavy)
        registered = registry.publish(self.stage, self.uri, self.client)
        self.assertEqual(candidate["l1"]["onnxSha256"], tracking.digest(self.fixture.l1))
        self.assertEqual(registered["kind"], "heavy")
    def test_manifest_extension_preserves_legacy_fields_and_cannot_overwrite(self):
        self.prepare(); registry.publish(self.stage, self.uri, self.client)
        vectors = self.root / "vectors.csv"; vectors.write_bytes(b"fixture bytes")
        legacy = self.root / "release.json"
        document = {"schemaVersion": 1, "releaseId": "example", "featureAsOf": "2026-02-01T00:00:00Z",
            "transformPath": "transform.json", "vectorsPath": "vectors.csv",
            "transformSha256": tracking.digest(self.fixture.transform), "vectorsSha256": tracking.digest(vectors),
            "candidateLimits": {"graph": 2000, "heuristic": 1000, "embedding": 1000}, "minValidationAuc": .5}
        registry.immutable_json(legacy, document); before = legacy.read_bytes()
        result = registry.extend_release(legacy, [self.stage], self.root / "other/v2.json")
        for key in ("releaseId", "featureAsOf", "transformSha256", "vectorsSha256", "candidateLimits", "minValidationAuc"):
            self.assertEqual(result[key], document[key])
        self.assertEqual(Path(result["vectorsPath"]), vectors)
        self.assertEqual(before, legacy.read_bytes())
        with self.assertRaises(FileExistsError): registry.extend_release(legacy, [self.stage], self.root / "other/v2.json")
    def test_wrong_release_transform_fails_closed(self):
        self.prepare(); registry.publish(self.stage, self.uri, self.client)
        registry.immutable_json(self.root / "release.json", {"schemaVersion": 1, "transformSha256": "0" * 64})
        with self.assertRaisesRegex(ValueError, "transform mismatch"):
            registry.extend_release(self.root / "release.json", [self.stage], self.root / "v2.json")
    def test_changed_tracking_source_blocks_registry(self):
        candidate = self.prepare()
        self.client.runs[candidate["sources"][0]["runId"]]["tags"]["pymk.publication"] = "incomplete"
        with self.assertRaises(ValueError): registry.publish(self.stage, self.uri, self.client)
        self.assertFalse(self.client.models)
    def test_concurrent_publisher_lock_rejects_second_writer(self):
        self.prepare()
        with tracking.lock(self.stage):
            with self.assertRaises(FileExistsError): registry.publish(self.stage, self.uri, self.client)
    def test_changed_tracking_output_is_rejected_before_staging(self):
        self.fixture.l1.write_bytes(b"changed")
        with self.assertRaises(ValueError): self.prepare()
        self.assertFalse(self.stage.exists())
    def test_mutated_remote_artifact_is_detected_after_registration(self):
        self.prepare(); result = registry.publish(self.stage, self.uri, self.client)
        self.client.runs[result["runId"]]["files"]["candidate/" + result["candidateSha256"] + "/model.onnx"] = b"changed"
        with self.assertRaisesRegex(ValueError, "checksum"): registry.publish(self.stage, self.uri, self.client)


if __name__ == "__main__": unittest.main()
