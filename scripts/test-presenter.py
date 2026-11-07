"""Focused checks for reset admission, retry receipts and filesystem scope without touching application data."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("presenter", Path(__file__).with_name("presenter-reset.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PresenterTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.control = self.root / "runtime/presenter"
        self.control.mkdir(parents=True)
        self.receipts = self.control / "requests"
        self.receipts.mkdir()
        for name, value in dict(ROOT=self.root, CONTROL=self.control, PAUSE=self.control/"pause",
                                GATE=self.control/"maintenance", STATUS=self.control/"status.json",
                                LOCK=self.control/"operation.lock", RECEIPTS=self.receipts).items():
            p = patch.object(module, name, value); p.start(); self.addCleanup(p.stop)

    def test_duplicate_command_does_not_spawn_a_second_reset(self):
        key = str(uuid.uuid4())
        with patch.object(module.subprocess, "Popen") as launch:
            first = module.start("CLEAR", key)
            second = module.start("CLEAR", key)
            self.assertEqual(first["requestId"], second["requestId"])
            self.assertEqual(1, launch.call_count)
            self.assertEqual("FAILED", second["state"])  # A crashed/missing worker stays retryable.
            self.assertEqual(409, module.start("A2A", key)["httpStatus"])

    def test_concurrent_reset_is_rejected(self):
        with module.LOCK.open("a") as lease:
            module.fcntl.flock(lease, module.fcntl.LOCK_EX | module.fcntl.LOCK_NB)
            with patch.object(module.subprocess, "Popen") as launch:
                self.assertEqual(409, module.start("CLEAR", str(uuid.uuid4()))["httpStatus"])
                launch.assert_not_called()

    def test_only_enumerated_runtime_data_is_removed(self):
        runtime=self.root/"runtime"
        (runtime/"customer-engine.mv.db").write_text("customer history")
        (runtime/"api-token").write_text("credential")
        (runtime/"backups").mkdir()
        (runtime/"backups/saved").write_text("keep")
        (runtime/"scratch").mkdir()
        (runtime/"scratch/work").write_text("remove")
        module.clear_files()
        self.assertFalse((runtime/"customer-engine.mv.db").exists())
        self.assertFalse((runtime/"scratch").exists())
        self.assertEqual("credential",(runtime/"api-token").read_text())
        self.assertEqual("keep",(runtime/"backups/saved").read_text())

    def test_preflight_failure_does_not_enter_maintenance(self):
        with patch.object(module, "held", return_value=False):
            module.work("CLEAR", str(uuid.uuid4()))
        self.assertFalse(module.GATE.exists())
        self.assertFalse(module.PAUSE.exists())
        self.assertEqual("FAILED", module.read_json(module.STATUS)["state"])

    def test_symlink_cannot_reset_another_directory(self):
        outside=self.root/"outside";outside.mkdir();(outside/"keep").write_text("keep")
        (self.root/"runtime/scratch").symlink_to(outside,target_is_directory=True)
        with self.assertRaises(RuntimeError):
            module.clear_files()
        self.assertTrue((outside/"keep").exists())


if __name__ == "__main__":
    unittest.main()
