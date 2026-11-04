import unittest
from unittest.mock import patch

import test_registry as fixtures
from pymk_training import model_sync, registry, tracking


class ModelSyncTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.RegistryTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        f = self.fixture
        f.prepare()
        registry.publish(f.stage, f.uri, f.client)
        vectors = f.root / "vectors.csv"
        vectors.write_bytes(b"unit fixture")
        legacy = f.root / "legacy.json"
        registry.immutable_json(legacy, {"schemaVersion": 1, "releaseId": "sync-test", "vectorsPath": str(vectors),
            "vectorsSha256": tracking.digest(vectors), "transformPath": str(f.fixture.transform),
            "transformSha256": tracking.digest(f.fixture.transform)})
        self.release = f.root / "release.json"
        registry.extend_release(legacy, [f.stage], self.release)
        self.output = f.root / "downloaded"

    def sync(self):
        f = self.fixture
        with patch.object(registry.subprocess, "run", side_effect=fixtures.java_evidence):
            return model_sync.sync(self.release, self.output, f.uri, f.jar, f.client)

    def test_downloads_exact_bytes_and_preserves_pins_without_activation(self):
        f = self.fixture
        f.client.aliases["champion"] = "999"
        result = self.sync()
        self.assertEqual((self.output / "light/model.onnx").read_bytes(), (f.stage / "model.onnx").read_bytes())
        self.assertFalse(result["automaticPromotion"])
        self.assertEqual(registry.read(self.output / "release.json")["registry"], registry.read(self.release)["registry"])
        self.assertFalse((self.output / "approval.json").exists())
        with self.assertRaises(FileExistsError): self.sync()

    def test_corrupted_download_has_no_completion_record(self):
        self.fixture.client.corrupt = True
        with self.assertRaises((ValueError, KeyError)): self.sync()
        self.assertFalse((self.output / "sync.json").exists())

    def test_changed_pinned_version_is_rejected(self):
        next(iter(self.fixture.client.versions.values())).source = "other"
        with self.assertRaisesRegex(ValueError, "Pinned version"): self.sync()
        self.assertFalse((self.output / "sync.json").exists())

    def test_java_failure_is_not_a_synchronized_candidate(self):
        f = self.fixture
        with patch.object(registry.subprocess, "run", return_value=fixtures.SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(ValueError, "Java validation"):
                model_sync.sync(self.release, self.output, f.uri, f.jar, f.client)
        self.assertFalse((self.output / "sync.json").exists())

    def test_unacknowledged_run_is_rejected(self):
        for value in self.fixture.client.versions.values():
            self.fixture.client.runs[value.run_id]["status"] = "FAILED"
        with self.assertRaisesRegex(ValueError, "Unacknowledged"): self.sync()
