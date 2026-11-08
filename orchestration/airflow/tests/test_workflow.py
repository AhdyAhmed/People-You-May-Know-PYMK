import copy
from contextlib import nullcontext
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
        self.jar_patch = patch.object(w, "JAR", str(self.file))
        self.jar_patch.start()
        self.addCleanup(self.jar_patch.stop)
        self.claim_patch = patch.object(w.recovery, "claim", side_effect=lambda *args: nullcontext("test-key"))
        self.claim_patch.start()
        self.addCleanup(self.claim_patch.stop)
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

    def backfill(self):
        plan = self.candidate()
        plan["workflow"] = "backfill"
        for i, item in enumerate(plan["history"]):
            item.update(knowledgeCutoff="2026-01-24T00:00:00Z", featureSourceRevision="2", labelSourceRevision="3")
            for name, schema in (("light", 1), ("heavy", 2)):
                csv = self.root / f"{name}-{i}.csv"
                csv.write_text("retained immutable fixture")
                metadata = self.root / f"{name}-{i}.json"
                metadata.write_text(json.dumps({"schemaVersion": schema, "releaseId": item["releaseId"],
                    "featureAsOf": item["featureAsOf"], "labelWindowEnd": item["labelWindowEnd"],
                    "csvSha256": w.digest(csv), "transformSha256": self.ref["sha256"],
                    "featureSourceRevision": "2", "labelSourceRevision": "3", "exportedAt": item["knowledgeCutoff"]}))
                item[name] = {"csv": w.reference(csv), "provenance": w.reference(metadata)}
        return plan

    def test_backfill_restores_exact_bytes_without_database_or_cli(self):
        plan = self.backfill()
        path = self.root / "backfill.json"
        w.save(path, plan)
        ref = w.reference(path)
        with patch.object(w, "batch", side_effect=AssertionError("Current database queried")), \
             patch.object(w, "cli", side_effect=AssertionError("Unexpected computation")):
            previous = w.execute(ref, "validate")
            result = w.read(w.execute(ref, "restore", previous))
        self.assertEqual(len(result["results"]["heavyInputs"]), 3)
        self.assertEqual(w.digest(result["results"]["heavyInputs"][0]), plan["history"][0]["heavy"]["csv"]["sha256"])
        self.assertFalse(result["automaticPromotion"])
        self.assertEqual(w.read(w.execute(ref, "restore", previous)), result)

    def test_backfill_rejects_missing_mutated_or_wrong_historical_inputs(self):
        plan = self.backfill()
        for field, value in (("featureSourceRevision", "999"), ("labelSourceRevision", "999"),
                             ("knowledgeCutoff", "2026-01-23T00:00:00Z")):
            invalid = copy.deepcopy(plan)
            invalid["history"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): w.validate(invalid)
        csv = w.resolve(plan["history"][0]["heavy"]["csv"])
        csv.write_text("changed")
        with self.assertRaisesRegex(ValueError, "checksum"): w.validate(plan)
        csv.unlink()
        with self.assertRaises(FileNotFoundError): w.validate(plan)

    def test_reconciliation_requires_bounded_event_and_ingestion_watermarks(self):
        plan = {key: self.plan[key] for key in ("schemaVersion", "intervalStart", "intervalEnd", "outputVersion")}
        plan.update(workflow="reconcile", windowStart="2026-01-01T00:00:00Z", knowledgeCutoff="2026-01-03T00:00:00Z")
        self.assertEqual(w.validate(plan), plan)
        for field, value in (("windowStart", "2025-01-01T00:00:00Z"), ("windowStart", plan["intervalEnd"]),
                             ("knowledgeCutoff", "2026-01-02T00:00:00Z"), ("knowledgeCutoff", "2099-01-01T00:00:00Z")):
            with self.subTest(field=field), self.assertRaises(ValueError): w.validate({**plan, field: value})

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
        self.assertEqual(w.execute(ref, "validate"), receipt)
        relocated = self.root / "same-plan-new-trigger.json"
        w.save(relocated, self.plan)
        self.assertEqual(w.execute(w.reference(relocated), "validate"), receipt)
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

    def test_approved_activation_is_disabled_and_manifest_epoch_bound(self):
        release = self.root / "release.json"
        w.save(release, {"schemaVersion": 2, "releaseId": "approved"})
        approval = self.root / "approval.json"
        w.save(approval, {"expectedEpoch": 5, "manifestSha256": w.digest(release),
            "approvedAt": "2026-01-02T00:00:00Z", "expiresAt": "2026-01-04T00:00:00Z"})
        plan = {key: self.plan[key] for key in ("schemaVersion", "intervalStart", "intervalEnd", "outputVersion", "sourceRevision")}
        plan.update(workflow="approved_activation", release=w.reference(release), approval=w.reference(approval),
                    expectedEpoch=5, legacyModel=self.ref, legacyMetadata=self.ref, operatorDrainConfirmed=True)
        now = w.instant("2026-01-03T12:00:00Z")
        with patch.dict("os.environ", {"PYMK_WORKFLOW_ALLOW_ACTIVATION": "false"}), self.assertRaisesRegex(ValueError, "disabled"):
            w.validate(plan, now)
        with patch.dict("os.environ", {"PYMK_WORKFLOW_ALLOW_ACTIVATION": "true"}):
            self.assertEqual(w.validate(plan, now), plan)
            with self.assertRaisesRegex(ValueError, "epoch"): w.validate({**plan, "expectedEpoch": 6}, now)
            with self.assertRaisesRegex(ValueError, "drained"): w.validate({**plan, "operatorDrainConfirmed": False}, now)
            with self.assertRaisesRegex(ValueError, "Expired"): w.validate(plan, w.instant("2026-01-05T00:00:00Z"))

    def test_command_reuses_verified_receipt_and_blocks_corruption(self):
        output = self.root / "output.json"
        log = self.root / "command.log"
        def write_output(*args, **kwargs): output.write_text("completed")
        with patch("subprocess.run", side_effect=write_output) as run:
            w.command(["fixed-worker"], log)
            w.command(["fixed-worker"], log)
            self.assertEqual(run.call_count, 1)
        with self.assertRaisesRegex(ValueError, "identity"): w.command(["different-worker"], log)
        output.write_text("corrupt")
        with self.assertRaisesRegex(ValueError, "checksum"): w.command(["fixed-worker"], log)

    def test_uncertain_partial_command_is_not_blindly_reexecuted(self):
        log = self.root / "partial.log"
        log.write_text("worker crashed")
        with patch("subprocess.run") as run, self.assertRaisesRegex(ValueError, "Uncertain"):
            w.command(["fixed-worker"], log)
        run.assert_not_called()

    def test_actual_timeout_leaves_no_success_receipt(self):
        log = self.root / "timeout.log"
        with self.assertRaises(subprocess.TimeoutExpired):
            w.command([sys.executable, "-c", "import time; time.sleep(5)"], log, timeout=0.1)
        self.assertFalse(log.with_suffix(".completed.json").exists())
        with self.assertRaisesRegex(ValueError, "Uncertain"):
            w.command([sys.executable, "-c", "print('must not run')"], log)

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
