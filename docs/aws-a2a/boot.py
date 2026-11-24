"""Start the demo bank owner or one network and supervise its processes."""

import base64
import hmac
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

from bridge import ROLES

HOME = Path("/opt/ogm")
RUNTIME = HOME / "runtime"
JAR = "ai-service/target/ai-simulation-1.0.0.jar"
CA = "/etc/ssl/certs/ogm-rds-bundle.pem"
CHILDREN = []


def materialize_tokens(seed):
    """Build the stable runtime files expected by Main and AiProxy."""
    def token(label):
        value = hmac.digest(seed.encode(), label.encode(), "sha256")
        return base64.urlsafe_b64encode(value).decode().rstrip("=")

    def write(file, content):
        file.parent.mkdir(exist_ok=True, parents=True)
        if file.is_symlink():
            raise RuntimeError(f"Unexpected symbolic link at {file}")
        if file.exists() and file.read_text().strip() != content.strip():
            raise RuntimeError(f"Existing credential differs at {file}")
        file.write_text(content, encoding="utf-8")
        file.chmod(0o600)

    write(RUNTIME / "api-token", token("owner/main"))
    write(RUNTIME / "marketing-api-token", token("owner/marketing"))
    ai = RUNTIME / "ai"
    write(ai / "policy-reader-token", token("policy/reader"))
    for _, name, roles in ROLES.values():
        folder = ai if name == "SIMULATION" else ai / name.lower()
        principals = ("analyst", "policyAnalyst", "outcomes") + tuple(role for _, role in roles)
        master = {principal: token(name + "/" + principal) for principal in principals}
        write(folder / "credentials.json", json.dumps(master, separators=(",", ":")))
        write(folder / "analyst-token", master["analyst"])
        for _, role in roles:
            scoped = master if role == "ORCHESTRATOR" else {
                role: master[role], "ORCHESTRATOR": master["ORCHESTRATOR"]
            }
            if role == "RESEARCH":
                scoped["analyst"] = master["policyAnalyst"]
            write(folder / (role.lower() + "-credentials.json"), json.dumps(scoped, separators=(",", ":")))


def hostname(name):
    """Accept only an infrastructure-supplied DNS name or IPv4 address."""
    result = os.environ[name]
    if not re.fullmatch(r"[A-Za-z0-9.-]+", result):
        raise RuntimeError(f"Invalid host in {name}")
    return result


def spawn(args, **env):
    """Start a child in its own process group for bounded shutdown."""
    child = subprocess.Popen(args, env={**os.environ, **env}, start_new_session=True)
    CHILDREN.append(child)


def relay(port, bind, target, target_port):
    """Bind a fixed TCP relay for the Java code's loopback peer URLs."""
    spawn(["socat", f"TCP-LISTEN:{port},bind={bind},reuseaddr,fork",
           f"TCP:{target}:{target_port},connect-timeout=3"])


def agent(mode):
    """Preserve Java role listener ports and expose only fixed peer relays."""
    domain = hostname("PEER_DOMAIN")
    owner = hostname("OWNER_HOST")
    base, name, roles = ROLES[mode]
    os.environ.update(
        AI_ENGINE_URL=f"http://{owner}:8088/api/v1/",
        AI_MARKETING_URL=f"http://{owner}:8089/api/v1/",
        AI_POLICY_DIR=str(HOME / "policy-documents"),
    )
    relay(base + 10000, "0.0.0.0", "127.0.0.1", base)
    if mode == "1":
        relay(18101, "0.0.0.0", "127.0.0.1", 8101)
        relay(8110, "127.0.0.1", "feedback." + domain, 18110)
        relay(8120, "127.0.0.1", "personalization." + domain, 18120)
    if mode == "2":
        relay(8101, "127.0.0.1", "simulation." + domain, 18101)
    for _, role in roles:
        spawn(["java", "-Dengine.home=/opt/ogm", "-jar", JAR], AI_NETWORK=name, AI_ROLE=role)
    spawn([sys.executable, str(HOME / "cloud/bridge.py")])


def bank():
    """Start the existing owner launcher on its persistent local runtime."""
    domain = hostname("PEER_DOMAIN")
    relay(8088, "0.0.0.0", "127.0.0.1", 8090)
    relay(8089, "0.0.0.0", "127.0.0.1", 8092)
    for port, peer in ((8100, "simulation"), (8110, "feedback"), (8120, "personalization")):
        relay(port, "127.0.0.1", peer + "." + domain, port + 10000)
    spawn(["bash", "scripts/run.sh"], PRESENTER_GROUP="base")


def stop():
    """Stop all child process groups, including forked relays."""
    for child in CHILDREN:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    expires = time.monotonic() + 20
    for child in CHILDREN:
        try:
            child.wait(timeout=max(0, expires - time.monotonic()))
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


def main():
    """Read the provisioned configuration and fail if any role stops."""
    os.chdir(HOME)
    os.umask(0o077)
    seed = os.environ.pop("OGM_BANK_SEED")
    database_password = os.environ.pop("OGM_APP_DB_PASSWORD")
    if not re.fullmatch(r"[A-Za-z0-9]{32,64}", seed) or not re.fullmatch(
        r"[A-Za-z0-9]{32,64}", database_password
    ):
        raise RuntimeError("Expected the generated alphanumeric bank and database secrets")
    materialize_tokens(seed)
    db_host = hostname("DB_HOST")
    db = f"postgresql://ogm_app:{database_password}@{db_host}:5432/ogm?sslmode=verify-full&sslrootcert={CA}"
    os.environ.update(
        LENDING_BANK="emma", DATABASE_URL=db, AI_DATABASE_URL=db,
        AI_OUTCOME_SOURCE_ID="emma-aws-demo", AI_OUTCOME_SOURCE_MODE="DEMO",
        AI_BASE_PORT="8100", AI_FEEDBACK_BASE_PORT="8110", AI_PERSONALIZATION_BASE_PORT="8120",
    )
    mode = os.environ.get("NETWORK_ID", "owner")
    if mode not in (*ROLES, "owner"):
        raise RuntimeError("Invalid deployment mode")
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: sys.exit(0))
    try:
        if mode == "owner":
            bank()
        else:
            agent(mode)
        while True:
            if any(child.poll() is not None for child in CHILDREN):
                raise RuntimeError("A required process exited. Inspect the task logs.")
            time.sleep(1)
    finally:
        stop()


if __name__ == "__main__":
    main()
