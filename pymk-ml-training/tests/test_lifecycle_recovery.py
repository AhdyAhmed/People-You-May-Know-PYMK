import tempfile
from pathlib import Path
import unittest

from day40_lifecycle_probe import main


class LifecycleBackupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "metadata.dump").write_bytes(b"database fixture")
        (self.root / "artifacts").mkdir()
        self.model = self.root / "artifacts/model.onnx"
        self.model.write_bytes(b"artifact fixture")
        self.args = ["--root", str(self.root), "--uri", "http://127.0.0.1:5000", "--jar", str(self.root / "unused.jar")]

    def test_backup_inventory_verifies_and_cannot_be_overwritten(self):
        main(["backup", *self.args])
        main(["verify-backup", *self.args])
        with self.assertRaises(FileExistsError): main(["backup", *self.args])

    def test_changed_metadata_is_rejected(self):
        main(["backup", *self.args])
        (self.root / "metadata.dump").write_bytes(b"changed")
        with self.assertRaisesRegex(AssertionError, "Backup bytes"): main(["verify-backup", *self.args])

    def test_missing_or_extra_artifact_is_rejected(self):
        main(["backup", *self.args])
        extra = self.root / "artifacts/unexpected"
        extra.write_bytes(b"extra")
        with self.assertRaisesRegex(AssertionError, "Backup bytes"): main(["verify-backup", *self.args])
        extra.unlink()
        self.model.unlink()
        with self.assertRaises(AssertionError): main(["verify-backup", *self.args])

    def test_changed_tracking_identity_is_rejected(self):
        main(["backup", *self.args])
        changed = [value.replace(":5000", ":5001") for value in self.args]
        with self.assertRaisesRegex(AssertionError, "original tracking identity"): main(["verify-backup", *changed])
