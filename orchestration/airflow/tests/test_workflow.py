import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
sys.path.insert(0, "/opt/pymk")
import workflow as w


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.patch = patch.object(w, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.patch.stop)
        self.file = self.root / "transform.json"
        self.file.write_text(json.dumps({"payload": {"metadata": {"fitCutoff": "2026-01-01T00:00:00Z"}}}))
        self.ref = w.reference(self.file)
        self.plan = {"schemaVersion": 1, "workflow": "feature", "intervalStart": "2026-01-02T00:00:00Z",
                     "intervalEnd": "2026-01-03T00:00:00Z", "outputVersion": "test-v1", "sourceRevision": "3",
                     "transform": self.ref, "profiles": self.ref}

    def candidate(self):
        plan = {**self.plan, "workflow": "candidate", "intervalStart": "2026-01-23T00:00:00Z", "intervalEnd": "2026-01-25T00:00:00Z"}
        del plan["profiles"]
        plan.update(evaluationPolicy=self.ref, blendPolicy=self.ref, context=self.ref,
            history=[{"releaseId": f"r{i}", "featureAsOf": f"2026-01-{day:02d}T00:00:00Z",
                      "labelWindowEnd": f"2026-01-{day+7:02d}T00:00:00Z"} for i, day in enumerate((3,10,17))])
        return plan

    def test_valid_feature_and_mature_candidate(self):
        self.assertEqual(w.validate(self.plan), self.plan)
        self.assertEqual(w.validate(self.candidate())["workflow"], "candidate")

    def test_maturity_rejects_future_and_overlapping_labels(self):
        plan = self.candidate()
        plan["history"][-1]["labelWindowEnd"] = "2026-01-26T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "mature"): w.validate(plan)
        plan = self.candidate()
        plan["history"][0]["labelWindowEnd"] = "2026-01-11T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "Overlapping"): w.validate(plan)

    def test_unsafe_or_unknown_parameters(self):
        for field, value in (("outputVersion", "../escape"), ("sourceRevision", "current"),
                             ("intervalEnd", "2099-01-03T00:00:00Z"), ("workflow", "activate")):
            with self.subTest(field=field), self.assertRaises(ValueError): w.validate({**self.plan, field: value})
        with self.assertRaises(ValueError): w.validate({**self.plan, "command": "arbitrary"})

    def test_checksum_and_path_confinement(self):
        with self.assertRaises(ValueError): w.resolve({**self.ref, "sha256": "0"*64})
        with self.assertRaises(ValueError): w.confined(self.root.parent / "outside")

    def test_no_overwrite_or_unbound_predecessor(self):
        path = self.root / "plan.json"
        path.write_text(json.dumps(self.plan))
        ref = w.reference(path)
        receipt = w.execute(ref, "validate")
        with self.assertRaises(FileExistsError): w.execute(ref, "validate")
        changed = copy.deepcopy(self.plan)
        changed["outputVersion"] = "different"
        other = self.root / "other.json"
        other.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, "predecessor"): w.execute(w.reference(other), "embeddings", receipt)

    def test_subprocess_failures_and_timeout_propagate(self):
        for error in (subprocess.CalledProcessError(2, ["java"]), subprocess.TimeoutExpired(["java"], 1)):
            with patch("subprocess.run", side_effect=error), self.assertRaises(type(error)):
                w.command(["java"], self.root / (type(error).__name__ + ".log"))

    def test_tracking_outage_does_not_claim_success(self):
        with patch.dict("os.environ", {"PYMK_WORKFLOW_TRACKING_URI": ""}), self.assertRaises(ValueError):
            w.cli("train_light_ranker", [], self.root, tracked=True)

    def test_blend_context_is_not_sent_as_full_funnel_evaluation_context(self):
        plan = self.candidate()
        path = self.root / "candidate.json"
        path.write_text(json.dumps(plan))
        ref = w.reference(path)
        results = {"heavyInputs": [], "heavyModel": "heavy.onnx", "heavyMetadata": "heavy.json",
                   "lightModel": "light.onnx", "lightMetadata": "light.json"}
        for step, previous in (("evaluate", "heavy"), ("blend", "evaluate")):
            predecessor = self.root / (step + "-predecessor.json")
            predecessor.write_text(json.dumps({"plan": ref, "step": previous, "artifacts": [], "results": results}))
            with patch.object(w, "cli", return_value=self.root / "bundle") as launch:
                w.execute(ref, step, w.reference(predecessor))
            arguments = launch.call_args.args[1]
            self.assertEqual("--context" in arguments, step == "blend")

    def test_scheduled_envelope_matches_exact_interval(self):
        path = self.root / "plan.json"
        path.write_text(json.dumps(self.plan))
        ref = w.reference(path)
        inbox = self.root / "inbox/feature"
        inbox.mkdir(parents=True)
        (inbox / "20260103T000000Z.json").write_text(json.dumps(ref))
        start, end = w.instant(self.plan["intervalStart"]), w.instant(self.plan["intervalEnd"])
        self.assertEqual(w.select_plan("feature", None, start, end), ref)
        with self.assertRaisesRegex(ValueError, "interval"):
            w.select_plan("feature", None, end, end)
        with self.assertRaises(FileNotFoundError):
            w.select_plan("candidate", None, start, end)
        # Manual historical work uses its explicit manifest, not the trigger date.
        self.assertEqual(w.select_plan("feature", ref, None, None), ref)
        with self.assertRaises(ValueError): w.select_plan("candidate", ref, None, None)


if __name__ == "__main__": unittest.main()
