#!/usr/bin/env python3
"""Serialize local maintenance, restore an API-verified start, and return exact prepared identities for the presenter."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import uuid
from urllib import request

ROOT = Path(__file__).resolve().parents[1]
CONTROL = ROOT / "runtime/presenter"
PAUSE = CONTROL / "pause"
GATE = CONTROL / "maintenance"
STATUS = CONTROL / "status.json"
LOCK = CONTROL / "operation.lock"
RECEIPTS = CONTROL / "requests"
PORTS = [2323, 8090, 8091, 8092, *range(8100, 8106), 8110, 8111, 8114, 8115, 8116, 8120, 8122, 8126, 8127, 8128]


def write_json(path, value):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value), encoding="utf-8")
    temp.replace(path)


def read_json(path):
    return json.loads(path.read_text()) if path.exists() else {"state": "IDLE"}


def held(path):
    with path.open("a") as lease:
        try:
            fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return False
        except BlockingIOError:
            return True


def status():
    job = read_json(STATUS)
    if job["state"] == "RUNNING" and not held(LOCK):
        return dict(job, state="FAILED", message="Preparation was interrupted. Retry from Presenter controls.")
    return job


def wait_until(check, seconds, message):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(.4)
    raise RuntimeError(message)


def port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), .2):
            return True
    except OSError:
        return False


def api(path):
    token = (ROOT / "runtime/api-token").read_text().strip()
    req = request.Request("http://127.0.0.1:8090/api/v1/" + path,
                          headers={"Authorization": "Bearer " + token})
    with request.build_opener(request.ProxyHandler({})).open(req, timeout=4) as response:
        return json.load(response)


def fingerprint():
    text = os.environ.get("DATABASE_URL", "") + "\n" + os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092")
    return hashlib.sha256(text.encode()).hexdigest()


def park(groups):
    PAUSE.touch()
    wait_until(lambda: all((CONTROL / (g + ".paused")).exists() and held(CONTROL / (g + ".lock")) for g in groups)
               and not any(map(port_open, PORTS)), 90,
               "Workers did not stop cleanly. Close other local application processes, restart the launchers, and retry.")


def clear_files():
    runtime = (ROOT / "runtime").resolve()
    for name in ("customer-engine.mv.db", "customer-engine.trace.db", "business-simulations.mv.db",
                 "business-simulations.trace.db", "maintenance/a2a-filming-start.json", "scratch", "outbound"):
        target = runtime / name
        if target.is_symlink() or not target.resolve().is_relative_to(runtime):
            raise RuntimeError("A runtime data path leaves this installation; reset stopped.")
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink(missing_ok=True)


def work(action, key):
    job = {"state": "RUNNING", "action": action, "requestId": key}
    def report(state, message):
        job.update(state=state, message=message)
        write_json(STATUS, job)
        write_json(RECEIPTS / (key + ".json"), job)
    entered = False
    try:
        groups = [g for g in ("base", "ai") if held(CONTROL / (g + ".lock"))]
        if "base" not in groups:
            raise RuntimeError("Restart the base window with scripts/run.ps1, then retry.")
        for group in groups:
            if (CONTROL / (group + ".config")).read_text() != fingerprint():
                raise RuntimeError("The launchers use different database/broker settings. Restart all three windows with the same local configuration.")
        if action == "A2A" and ("ai" not in groups or not os.environ.get("OPENAI_API_KEY") or
                                (CONTROL / "ai.mode").read_text() != "full"):
            raise RuntimeError("Start all three A2A networks with scripts/run-ai.ps1 and the configured provider key, then retry.")
        if any(os.environ.get(k, v) != v for k, v in
               (("AI_ENGINE_URL", "http://127.0.0.1:8090/api/v1/"), ("AI_MARKETING_URL", "http://127.0.0.1:8092/api/v1/"))):
            raise RuntimeError("Presenter reset requires local owner APIs at their standard addresses.")
        if any(os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
            raise RuntimeError("Presenter reset requires standard local Java settings.")
        if any(os.environ.get(k, v) != v for k, v in
               (("AI_BASE_PORT", "8100"), ("AI_FEEDBACK_BASE_PORT", "8110"), ("AI_PERSONALIZATION_BASE_PORT", "8120"))):
            raise RuntimeError("Presenter reset requires the standard local network ports.")
        java = ["java", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", "services/target/customer-engine-1.0.0.jar", "com.lending.engine.web.PresenterStores"]
        subprocess.run(java + ["check"], check=True, timeout=80)
        if action == "A2A":
            # Check the exact fixture and existing presenter copies before removing data.
            import importlib.util
            spec = importlib.util.spec_from_file_location("fixture", ROOT / "scripts/seed-presenter-a2a.py")
            fixture = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(fixture)
            fixture.install_policies(ROOT, Path(os.environ.get("AI_POLICY_DIR", ROOT / "policy-documents")))
        entered = True
        GATE.touch()
        report("RUNNING", "Stopping local workers and draining active requests…")
        park(groups)
        report("RUNNING", "Clearing application history and queued events…")
        subprocess.run(java + ["clear"], check=True, timeout=120)
        clear_files()
        report("RUNNING", "Restarting workers and restoring the default catalog…")
        PAUSE.unlink(missing_ok=True)
        wait_until(lambda: api("health").get("status") == "UP" and port_open(8092) and bool(api("business/catalog").get("offers")),
                   150, "Base services did not restart. Check the base window, restart it if needed, and retry.")
        if "ai" in groups:
            wait_until(lambda: api("ai/health").get("status") == "UP", 120,
                       "AI services did not restart. Check the AI window, restart it if needed, and retry.")
        if action == "A2A":
            subprocess.run(["bash", "scripts/run-ai.sh", "--check"], check=True, timeout=100)
            report("RUNNING", "Preparing the filming customer, approved demo policies, draft and two fresh cohorts…")
            subprocess.run([sys.executable, "scripts/seed-presenter-a2a.py"], check=True, timeout=720)
            manifest = read_json(ROOT / "runtime/maintenance/a2a-filming-start.json")
            job["prepared"] = {field: manifest[field] for field in (
                "customerId", "customerName", "customerReference", "profileVersion",
                "draftId", "draftName", "draftVersion", "catalogOfferId", "catalogOfferName")}
            job["prepared"].update(discoverySeed=manifest["discoveryPopulation"]["seed"],
                                   validationSeed=manifest["validationPopulation"]["seed"])
        else:
            if api("customers?limit=1")["total"] != 0 or any(api("business/" + p) for p in ("publications", "drafts", "populations", "runs")):
                raise RuntimeError("The reset state was changed during maintenance. Close other clients and retry.")
        # Invalidate browser identities only after the reset has succeeded.
        (CONTROL / "epoch").write_text(key)
        GATE.unlink(missing_ok=True)
        report("READY", "A2A demo ready. Enter Business workspace to begin Network 1." if action == "A2A"
               else "Data cleared. Create your first customer from the landing page.")
    except Exception as problem:
        if entered or GATE.exists():
            PAUSE.touch()
        print(type(problem).__name__ + ": " + str(problem), file=sys.stderr, flush=True)
        message = str(problem) if isinstance(problem, RuntimeError) else "Preparation failed. Check runtime/presenter/reset.log, restart any exited launchers, then retry."
        report("FAILED", message)


def start(action, key):
    if action not in ("CLEAR", "A2A") or str(uuid.UUID(key)) != key:
        return {"error": "Invalid presenter command.", "httpStatus": 422}
    with (CONTROL / "admission.lock").open("a") as admission, LOCK.open("a") as lease:
        fcntl.flock(admission, fcntl.LOCK_EX)
        receipt = RECEIPTS / (key + ".json")
        if receipt.exists():
            old = read_json(receipt)
            if old.get("action") != action:
                return {"error": "Request ID already belongs to a different action.", "httpStatus": 409}
            if old["state"] == "RUNNING" and not held(LOCK):
                old.update(state="FAILED", message="Preparation was interrupted. Retry with a new request.")
            return old
        try:
            fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {"error": "Another presenter operation is already running.", "httpStatus": 409}
        job = {"state": "RUNNING", "action": action, "requestId": key, "message": "Checking the local demo configuration…"}
        write_json(STATUS, job)
        write_json(receipt, job)
        with (CONTROL / "reset.log").open("a") as log:
            subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "worker", action, key], cwd=ROOT,
                             pass_fds=(lease.fileno(),), start_new_session=True, stdin=subprocess.DEVNULL,
                             stdout=log, stderr=log)
        return job


if __name__ == "__main__":
    os.umask(0o077)
    os.chdir(ROOT)
    CONTROL.mkdir(parents=True, exist_ok=True)
    RECEIPTS.mkdir(exist_ok=True)
    if sys.argv[1:] == ["status"]:
        print(json.dumps(status()))
    elif len(sys.argv) == 4 and sys.argv[1] == "start":
        try:
            result = start(sys.argv[2], sys.argv[3])
        except ValueError:
            result = {"error": "Invalid presenter request ID.", "httpStatus": 422}
        print(json.dumps(result))
    elif len(sys.argv) == 4 and sys.argv[1] == "worker":
        work(sys.argv[2], sys.argv[3])
    else:
        sys.exit("Use status or start ACTION REQUEST_ID.")
