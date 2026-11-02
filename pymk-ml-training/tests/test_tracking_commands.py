"""Real CLI parity checks; optional live tracking is exercised by the Day 37 smoke."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from pymk_training import profile_embeddings, train_heavy_ranker, evaluate_heavy_ranker, tune_blend
import test_heavy_evaluation as evaluation_fixture
import test_blend_tuning as blend_fixture


class TrackingCommandsTest(unittest.TestCase):
    def tracking_args(self, root):
        args = ["--tracking-dir", str(root / "tracking")]
        if os.environ.get("PYMK_TEST_TRACKING_URI"):
            args += ["--tracking-uri", os.environ["PYMK_TEST_TRACKING_URI"]]
        return args

    def check_bundle(self, root, operation):
        matches = [p for p in (root / "tracking").glob("*/*/manifest.json")
                   if json.loads(p.read_bytes())["specification"]["operation"] == operation]
        self.assertEqual(len(matches), 1)
        payload = json.loads(matches[0].read_bytes())
        self.assertNotEqual(payload["outcome"], "failed")
        self.assertNotIn(str(root), matches[0].read_text())
        state = json.loads(matches[0].with_name("state.json").read_bytes())
        self.assertEqual(state["status"], "published" if os.environ.get("PYMK_TEST_TRACKING_URI") else "unpublished")
        if os.environ.get("PYMK_TEST_TRACKING_URI"):
            from mlflow import MlflowClient
            client = MlflowClient(tracking_uri=os.environ["PYMK_TEST_TRACKING_URI"])
            run = client.get_run(state["attempts"][-1]["runId"])
            self.assertEqual(run.data.tags["pymk.publication"], "complete")
            self.assertEqual(run.data.tags["pymk.outcome"], payload["outcome"])
            self.assertEqual(run.info.status, "FAILED" if payload["outcome"] == "not_met" else "FINISHED")
            self.assertEqual(run.data.metrics, payload["evidence"]["metrics"])
        return payload

    def test_profile_fit_tracking_preserves_transform_and_vectors(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            base = ["--input", str(Path(__file__).parent / "fixtures/profiles.csv"), "--fit",
                    "--as-of", "2026-01-03T00:00:00Z", "--embedding-version", "profile-test"]
            profile_embeddings.main(base + ["--artifact", str(root / "plain.json"), "--output", str(root / "plain.csv")])
            code = profile_embeddings.main(base + ["--artifact", str(root / "tracked.json"), "--output", str(root / "tracked.csv")] + self.tracking_args(root))
            self.assertEqual(code, 0)
            for suffix in ("json", "csv"):
                self.assertEqual((root / ("plain." + suffix)).read_bytes(), (root / ("tracked." + suffix)).read_bytes())
            payload = self.check_bundle(root, "profile")
            self.assertIn("fitProfilesSha256", payload["evidence"]["transform"])
            self.assertNotIn("vocabulary", json.dumps(payload))

    def test_heavy_training_and_frozen_evaluation_tracking_preserve_results(self):
        fixture = evaluation_fixture.HeavyEvaluationIntegrationTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        root = fixture.root; training = fixture.training
        inputs = sum((["--input", str(p)] for p in training.paths), [])
        args = inputs + ["--embedding-transform", str(training.transform), "--l1-model", str(training.l1),
                        "--l1-metadata", str(training.l1_metadata), "--model-version", "heavy", "--epochs", "12",
                        "--output-dir", str(root / "tracked-heavy")]
        self.assertEqual(train_heavy_ranker.main(args + self.tracking_args(root)), 0)
        self.assertEqual(fixture.model.read_bytes(), (root / "tracked-heavy/heavy.onnx").read_bytes())
        self.check_bundle(root, "heavy")
        plain = fixture.evaluate("plain-evaluation.json")
        args = inputs + ["--model", str(fixture.model), "--metadata", str(fixture.metadata),
                        "--l1-model", str(training.l1), "--l1-metadata", str(training.l1_metadata),
                        "--policy", str(fixture.policy), "--context", str(fixture.context),
                        "--output", str(root / "tracked-evaluation.json"), "--require-quality-gate"]
        with self.assertRaises(SystemExit) as stopped:
            evaluate_heavy_ranker.main(args + self.tracking_args(root))
        self.assertEqual(stopped.exception.code, 2)
        self.assertEqual(plain, json.loads((root / "tracked-evaluation.json").read_bytes()))
        self.assertEqual(self.check_bundle(root, "evaluate")["outcome"], "not_met")

    def test_blend_tracking_preserves_selection_and_configuration(self):
        fixture = blend_fixture.BlendTuningIntegrationTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        root = fixture.root; training = fixture.training
        plain = fixture.run_tuning("plain-blend")
        args = sum((["--input", str(p)] for p in training.paths), []) + [
            "--model", str(fixture.model), "--metadata", str(fixture.metadata),
            "--l1-model", str(training.l1), "--l1-metadata", str(training.l1_metadata),
            "--policy", str(fixture.policy), "--context", str(fixture.context), "--output-dir", str(root / "tracked-blend")]
        self.assertEqual(tune_blend.main(args + self.tracking_args(root)), 0)
        self.assertEqual(plain, json.loads((root / "tracked-blend/report.json").read_bytes()))
        self.assertEqual((root / "plain-blend/application-blend.properties").read_bytes(), (root / "tracked-blend/application-blend.properties").read_bytes())
        self.check_bundle(root, "blend")


if __name__ == "__main__": unittest.main()
