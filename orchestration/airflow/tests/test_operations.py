import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
sys.path.insert(0, "/opt/pymk")
import operations as ops


class OperationsTest(unittest.TestCase):
    def test_metrics_preserve_unknown_freshness(self):
        text = ops.prometheus({"groups": [dict(workflow="feature", step="stage", state="BLOCKED",
            items=1, failedAttempts=2, maxLastAttemptSeconds=3, freshnessSeconds=None)]})
        self.assertIn('pymk_workflow_freshness_seconds{workflow="feature",step="stage",state="BLOCKED"} NaN', text)
        self.assertNotIn("output_version", text)

    def test_retention_dry_run_and_quiesced_archive_preserve_model_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "requests").mkdir()
            request = root / "requests" / ("a"*32 + ".json")
            request.write_text("{}")
            os.utime(request, (0, 0))
            model = root / "model.onnx"
            model.write_bytes(b"must remain")
            self.assertEqual(len(ops.archive_requests(root)["archivedRequests"]), 1)
            self.assertTrue(request.exists())
            with self.assertRaisesRegex(ValueError, "quiescence"):
                ops.archive_requests(root, apply=True)
            connection = MagicMock()
            connection.cursor.return_value.__enter__.return_value.fetchone.return_value = (0,)
            with patch.object(ops.recovery, "connect", return_value=connection):
                result = ops.archive_requests(root, apply=True, quiesced=True)
            self.assertFalse(request.exists())
            self.assertEqual((root / "request-archive" / request.name).read_text(), "{}")
            self.assertEqual(model.read_bytes(), b"must remain")
            self.assertEqual(result["workflowArtifactsDeleted"], 0)

    def test_running_work_blocks_cleanup(self):
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value.fetchone.return_value = (1,)
        with tempfile.TemporaryDirectory() as root, patch.object(ops.recovery, "connect", return_value=connection):
            with self.assertRaisesRegex(ValueError, "RUNNING"):
                ops.archive_requests(root, apply=True, quiesced=True)
        with self.assertRaises(ValueError):
            ops.archive_requests(".", days=1)
