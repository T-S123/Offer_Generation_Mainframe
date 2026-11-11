"""Keep each Presenter launcher alive while its owned worker group drains, stops and restarts."""
import fcntl
import hashlib
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

def launcher_exited(child):
    """Observe exit without reaping the leader and releasing its process group ID."""
    return os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None


def workers_alive(group_id):
    """Check for live members of the session and process group created by the supervisor."""
    identity = str(group_id).encode("ascii")
    for process in Path("/proc").iterdir():
        if not process.name.isdecimal():
            continue
        try:
            state, _, group, session, *_ = (process / "stat").read_bytes().rsplit(b")", 1)[-1].split()
        except OSError:
            continue  # The process can exit between listing /proc and reading its status.
        if group == identity and session == identity and state not in {b"Z", b"X", b"x"}:
            return True
    return False


def wait_for_workers(child, seconds):
    """Wait for the whole group to release its resources before reaping the launcher."""
    deadline = time.monotonic() + seconds
    while True:
        if launcher_exited(child) and not workers_alive(child.pid):
            child.wait()
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(.1)


def halt(child, grace_seconds=55):
    """Drain the owned group, then terminate remaining members after the grace period."""
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    if wait_for_workers(child, grace_seconds):
        return
    print("Local workers exceeded the shutdown grace period; stopping the remaining owned processes.",
          file=sys.stderr, flush=True)
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    if not wait_for_workers(child, 5):
        raise RuntimeError("The owned worker group did not stop after SIGKILL.")


def supervise(group, command):
    """Acknowledge Presenter pause only after all owned workers have stopped."""
    control = Path("runtime/presenter")
    control.mkdir(parents=True, exist_ok=True)
    with (control / (group + ".lock")).open("a") as lease:
        try:
            fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            sys.exit(f"The {group} launcher is already running for this project.")
        database = os.environ.get("DATABASE_URL", "")
        if group == "ai":
            database = os.environ.get("AI_DATABASE_URL", database)
        identity = database + "\n" + os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092")
        (control / (group + ".config")).write_text(hashlib.sha256(identity.encode()).hexdigest())
        paused = control / (group + ".paused")
        if group == "ai":
            (control / "ai.mode").write_text("api-only" if "--api-only" in command or os.getenv("AI_API_ONLY") == "true" else "full")
        stopping = False

        def stop(signum, frame):
            nonlocal stopping
            stopping = True

        signal.signal(signal.SIGINT, stop)
        signal.signal(signal.SIGTERM, stop)
        child = None
        try:
            while not stopping:
                if (control / "pause").exists():
                    if child is not None:
                        active, child = child, None
                        halt(active)
                    if not stopping:
                        paused.touch()
                elif child is None:
                    paused.unlink(missing_ok=True)
                    child = subprocess.Popen(command, start_new_session=True,
                                             env={**os.environ, "PRESENTER_GROUP": group})
                elif launcher_exited(child):
                    break
                time.sleep(.25)
        finally:
            try:
                if child is not None:
                    halt(child)
            finally:
                paused.unlink(missing_ok=True)
        return child.returncode if child is not None and not stopping else 0


if __name__ == "__main__":
    group, *command = sys.argv[1:]
    sys.exit(supervise(group, command))
