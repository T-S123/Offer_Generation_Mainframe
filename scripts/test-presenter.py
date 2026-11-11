"""Checks resets, owned worker shutdown, cohort retries and selection evidence with disposable fixtures."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
from simulate_presenter_customers import aggregate, outcome

spec = importlib.util.spec_from_file_location("presenter", Path(__file__).with_name("presenter-reset.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
group_spec = importlib.util.spec_from_file_location("presenter_group", Path(__file__).with_name("presenter-group.py"))
supervisor = importlib.util.module_from_spec(group_spec)
group_spec.loader.exec_module(supervisor)


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

    def test_cohort_qualifies_before_selecting_and_counts_declines(self):
        class Owner:
            ready = False
            selections = []
            def get(self, path):
                if path == "customers/c":
                    return {"pipeline":{"state":"OFFERS_PENDING","runId":"run"},
                            "profiles":[{"data":{"monthlyIncomeUsd":5000,"monthlyDebtPaymentsUsd":300,"creditScore":730}}]}
                if path.startswith("marketing/runs/run/"):
                    return {"items":[{"outcome":"QUALIFIED","id":"q","riskId":"risk","campaignId":"campaign","offerId":"offer","offerVersion":1}],"total":1}
                if path == "bureau/requests/AUTO-B-q":
                    return {"result":{"status":"COMPLETED","decision":{"outcome":"APPROVED"}}}
                if path.startswith("offer-creation/customers/c/offers"):
                    return {"items":[{"id":"family","riskId":"risk","campaignId":"campaign","catalogOfferId":"offer",
                                      "catalogOfferVersion":1,"sourceVersion":2,"original":{"terms":{"amountUsd":1000}}}] if self.ready else []}
                raise AssertionError(path)
            def call(self, method, path, body):
                self.selections.append((method,path,body))
                return {"status":"SELECTED_DEMO_ONLY"}
        owner = Owner()
        self.assertIsNone(outcome(owner,"batch","c",{"offer":1}))
        owner.ready = True
        with patch("simulate_presenter_customers.random.Random") as random:
            random.return_value.random.return_value = .99
            random.return_value.choice.side_effect = lambda choices: choices[0]
            chosen = outcome(owner,"batch","c",{"offer":1})
        self.assertEqual(1,len(owner.selections))
        self.assertEqual("ORIGINAL",owner.selections[0][2]["kind"])
        report = aggregate([chosen,{**chosen,"selected":None}],{"offer":1})[0]
        self.assertEqual((2,2,1),(report["assessed"],report["eligible"],report["selected"]))

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

    def test_shutdown_reaps_launcher_and_stops_term_resistant_descendant(self):
        child = subprocess.Popen(["bash", "-c", 'trap "" TERM; sleep 20 & exit 7'],
                                 start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 2
            while not supervisor.launcher_exited(child) and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertTrue(supervisor.launcher_exited(child))
            self.assertTrue(supervisor.workers_alive(child.pid))
            supervisor.halt(child, grace_seconds=.15)
            self.assertEqual(7, child.returncode)
            self.assertFalse(supervisor.workers_alive(child.pid))
        finally:
            if child.returncode is None:
                supervisor.halt(child, grace_seconds=0)

    def test_only_current_cohort_receipts_can_resume(self):
        legacy = str(uuid.uuid4())
        action = "SIMULATE_CUSTOMERS"
        module.write_json(self.receipts / (legacy + ".json"),
                          {"state": "RUNNING", "action": action, "requestId": legacy})
        with patch.object(module.subprocess, "Popen") as launch:
            self.assertEqual("FAILED", module.start(action, legacy)["state"])
            launch.assert_not_called()
            key = str(uuid.uuid4())
            self.assertTrue(module.start(action, key)["resumable"])
            self.assertEqual("RUNNING", module.start(action, key)["state"])
            self.assertEqual(2, launch.call_count)

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
