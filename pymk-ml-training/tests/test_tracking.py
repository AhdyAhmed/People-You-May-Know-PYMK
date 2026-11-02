import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from pymk_training import tracking
from pymk_training.train_light_ranker import main as light_main
from test_light_ranker_training import _write_snapshots


class FakeClient:
    def __init__(self):
        self.runs = {}; self.fail = False; self.corrupt = False
    def get_experiment_by_name(self, name): return SimpleNamespace(experiment_id="1")
    def create_run(self, experiment_id, tags):
        run_id = str(len(self.runs) + 1)
        self.runs[run_id] = {"tags": dict(tags), "metrics": {}, "params": {}, "status": "RUNNING"}
        return SimpleNamespace(info=SimpleNamespace(run_id=run_id))
    def log_param(self, run, key, value, *, synchronous):
        assert synchronous is True
        self.runs[run]["params"][key] = value
    def log_metric(self, run, key, value, **kwargs): self.runs[run]["metrics"][key] = value
    def log_artifact(self, run, path, folder):
        if self.fail: raise ConnectionError("secret/path must not be recorded")
        self.runs[run]["artifact"] = Path(path).read_bytes()
    def download_artifacts(self, run, path, directory):
        target = Path(directory) / "manifest.json"
        target.write_bytes(b"corrupted" if self.corrupt else self.runs[run]["artifact"])
        return str(target)
    def set_tag(self, run, key, value, *, synchronous):
        assert synchronous is True
        self.runs[run]["tags"][key] = value
    def set_terminated(self, run, status): self.runs[run]["status"] = status


class TrackingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.output = self.root / "report.json"
        self.args = ["--output", str(self.output), "--tracking-dir", str(self.root / "tracking")]
    def work(self, argv):
        self.output.write_text(json.dumps({"qualityGate": {"status": "not_met", "reasons": ["no evidence"]},
            "metrics": {"auc": None, "precision": .5, "knownGroupCounts": {"private-employer": 2}},
            "inputs": [{"path": "secret-local-path", "member_id": 999}]}))
        return 0
    def bundle(self): return next((self.root / "tracking").glob("*/*/manifest.json")).parent
    def test_offline_bundle_is_filtered_and_quality_failure_is_explicit(self):
        self.assertEqual(tracking.cli("evaluate", self.args, self.work), 0)
        text = (self.bundle() / "manifest.json").read_text()
        self.assertNotIn("secret-local-path", text); self.assertNotIn("private-employer", text)
        payload = json.loads(text)
        self.assertEqual(payload["outcome"], "not_met")
        self.assertNotIn("metrics.auc", payload["evidence"]["metrics"])
        self.assertIn("metrics.auc", payload["evidence"]["unavailable"])
        client = FakeClient(); tracking.publish(self.bundle(), "http://localhost:5000", client)
        self.assertEqual(client.runs["1"]["status"], "FAILED")
        self.assertEqual(client.runs["1"]["tags"]["pymk.publication"], "complete")
    def test_retry_links_attempts_without_recomputing_and_published_retry_is_noop(self):
        tracking.cli("evaluate", self.args, self.work)
        before = self.output.read_bytes(); client = FakeClient(); client.fail = True
        with self.assertRaises(ConnectionError): tracking.publish(self.bundle(), "http://localhost:5000", client)
        state = json.loads((self.bundle() / "state.json").read_bytes())
        self.assertEqual(state["status"], "unpublished")
        self.assertNotIn("secret/path", json.dumps(state))
        client.fail = False
        tracking.publish(self.bundle(), "http://localhost:5000", client)
        tracking.publish(self.bundle(), "http://localhost:5000", client)
        self.assertEqual(len(client.runs), 2)
        self.assertEqual(client.runs["2"]["tags"]["pymk.retryOf"], "1")
        self.assertEqual(before, self.output.read_bytes())
    def test_upload_corruption_does_not_report_publication_success(self):
        tracking.cli("evaluate", self.args, self.work)
        client = FakeClient(); client.corrupt = True
        with self.assertRaises(ValueError): tracking.publish(self.bundle(), "http://localhost:5000", client)
        self.assertEqual(json.loads((self.bundle() / "state.json").read_bytes())["status"], "unpublished")
        self.assertEqual(client.runs["1"]["tags"]["pymk.publication"], "incomplete")
    def test_changed_output_or_manifest_is_rejected_before_network(self):
        tracking.cli("evaluate", self.args, self.work)
        self.output.write_text("changed")
        client = FakeClient()
        with self.assertRaises(ValueError): tracking.publish(self.bundle(), "http://localhost:5000", client)
        self.assertEqual(client.runs, {})
    def test_exception_records_failure_class_without_private_message(self):
        def broken(argv): raise ValueError("private member 123 and secret password")
        with self.assertRaises(ValueError): tracking.cli("evaluate", self.args, broken)
        content = (self.bundle() / "manifest.json").read_text()
        self.assertNotIn("private member", content)
        self.assertEqual(json.loads(content)["failureClass"], "ValueError")
        client = FakeClient(); tracking.publish(self.bundle(), "http://localhost:5000", client)
        self.assertEqual(client.runs["1"]["status"], "FAILED")
    def test_argument_failure_does_not_publish_stale_report(self):
        self.work([])  # An earlier invocation left a legitimate not_met report.
        def rejected(argv): raise SystemExit(2)
        with self.assertRaises(SystemExit): tracking.cli("evaluate", self.args, rejected)
        payload = json.loads((self.bundle() / "manifest.json").read_bytes())
        self.assertEqual(payload["outcome"], "failed")
        self.assertEqual(payload["outputs"], [])
        self.assertEqual(payload["evidence"]["metrics"], {})
    def test_changed_manifest_is_rejected_before_network(self):
        tracking.cli("evaluate", self.args, self.work)
        manifest = self.bundle() / "manifest.json"
        manifest.write_bytes(manifest.read_bytes() + b" ")
        client = FakeClient()
        with self.assertRaises(ValueError): tracking.publish(self.bundle(), "http://localhost:5000", client)
        self.assertFalse(client.runs)
    def test_mutated_input_cannot_be_a_successful_computation(self):
        source = self.root / "input.csv"; source.write_text("before")
        def changed(argv):
            source.write_text("after")
            return self.work(argv)
        code = tracking.cli("evaluate", self.args + ["--input", str(source)], changed)
        self.assertEqual(code, 2)
        payload = json.loads((self.bundle() / "manifest.json").read_bytes())
        self.assertEqual(payload["outcome"], "failed")
        self.assertFalse(payload["evidence"]["metrics"])
    def test_credentials_in_uri_are_rejected_without_binding_the_bundle(self):
        tracking.cli("evaluate", self.args, self.work)
        with self.assertRaises(ValueError): tracking.publish(self.bundle(), "http://user:secret@localhost:5000", FakeClient())
        self.assertFalse(json.loads((self.bundle() / "state.json").read_bytes())["attempts"])
    def test_publication_outage_returns_nonzero_and_preserves_outputs(self):
        with patch.object(tracking, "sdk_client", side_effect=ConnectionError("offline")):
            self.assertEqual(tracking.cli("evaluate", self.args + ["--tracking-uri", "http://localhost:5000"], self.work), 3)
        self.assertTrue(self.output.is_file())
        self.assertEqual(json.loads((self.bundle() / "state.json").read_bytes())["status"], "unpublished")
    def test_no_tracking_is_a_direct_call_without_identity_or_sdk(self):
        with patch.object(tracking, "identity", side_effect=AssertionError), patch.object(tracking, "sdk_client", side_effect=AssertionError):
            self.assertEqual(tracking.cli("light", ["--input", "anything"], lambda argv: 7), 7)
        self.assertFalse((self.root / "tracking").exists())
    def test_destination_cannot_change_after_an_uncertain_attempt(self):
        tracking.cli("evaluate", self.args, self.work)
        client = FakeClient(); client.fail = True
        with self.assertRaises(ConnectionError): tracking.publish(self.bundle(), "http://localhost:5000", client)
        with self.assertRaises(ValueError): tracking.publish(self.bundle(), "http://other:5000", client)
    def test_publication_lock_rejects_concurrent_publishers(self):
        tracking.cli("evaluate", self.args, self.work)
        with tracking.lock(self.bundle()):
            with self.assertRaises(FileExistsError): tracking.publish(self.bundle(), "http://localhost:5000", FakeClient())
    def test_light_cli_preserves_onnx_and_metrics_and_has_stable_logical_identity(self):
        paths = _write_snapshots(self.root / "data", snapshot_count=8)
        base = sum((["--input", str(path)] for path in paths), []) + ["--model-version", "light-test"]
        self.assertEqual(light_main(base + ["--output-dir", str(self.root / "plain")]), 0)
        for directory in ("tracked1", "tracked2"):
            self.assertEqual(light_main(base + ["--output-dir", str(self.root / directory), "--tracking-dir", str(self.root / "tracking")]), 0)
            self.assertEqual((self.root / "plain/light-test.onnx").read_bytes(), (self.root / directory / "light-test.onnx").read_bytes())
        manifests = list((self.root / "tracking").glob("*/*/manifest.json"))
        self.assertEqual(len(manifests), 2)
        first, second = [json.loads(path.read_bytes()) for path in manifests]
        self.assertEqual(first["logicalWorkId"], second["logicalWorkId"])
        self.assertNotEqual(first["computationAttempt"], second["computationAttempt"])
        self.assertEqual(first["evidence"]["metrics"], second["evidence"]["metrics"])
        self.assertEqual(set(first["evidence"]["partitionCohortSha256"]), {"train", "validation", "test"})


if __name__ == "__main__": unittest.main()
