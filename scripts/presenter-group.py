"""Keep the base/AI launch window alive while the local presenter pauses its own workers."""
import fcntl
import hashlib
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

group, *command = sys.argv[1:]
control = Path("runtime/presenter")
control.mkdir(parents=True, exist_ok=True)
lease = (control / (group + ".lock")).open("a")
try:
    fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit(f"The {group} launcher is already running for this project.")
database = os.environ.get("AI_DATABASE_URL", os.environ.get("DATABASE_URL", "")) if group == "ai" else os.environ.get("DATABASE_URL", "")
identity = database + "\n" + os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092")
(control / (group + ".config")).write_text(hashlib.sha256(identity.encode()).hexdigest())
paused = control / (group + ".paused")
stopping = False


def stop(signum, frame):
    global stopping
    stopping = True


def halt(child):
    if child is not None and child.poll() is None:
        os.killpg(child.pid, signal.SIGTERM)
        child.wait(timeout=55)


signal.signal(signal.SIGINT, stop)
signal.signal(signal.SIGTERM, stop)
if group == 'ai':
    (control / 'ai.mode').write_text('api-only' if '--api-only' in command or os.getenv('AI_API_ONLY') == 'true' else 'full')
child = None
try:
    while not stopping:
        if (control / "pause").exists():
            halt(child)
            child = None
            paused.touch()
        elif child is None:
            paused.unlink(missing_ok=True)
            child = subprocess.Popen(command, start_new_session=True,
                                     env={**os.environ, "PRESENTER_GROUP": group})
        elif child.poll() is not None:
            sys.exit(child.returncode)
        time.sleep(.25)
finally:
    halt(child)
    paused.unlink(missing_ok=True)
