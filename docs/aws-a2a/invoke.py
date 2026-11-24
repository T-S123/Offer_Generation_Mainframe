"""Invoke the deployed bank API from a signed-in AWS CloudShell session."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def access():
    """Assume the bank-scoped invoke role using CloudShell credentials."""
    result = subprocess.run([
        "aws", "sts", "assume-role", "--region", "us-east-1",
        "--role-arn", os.environ["OGM_CALLER_ROLE"],
        "--role-session-name", "ogm-console-guide", "--duration-seconds", "900",
        "--output", "json", "--no-cli-pager",
    ], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)["Credentials"]


def request(base, route, method, key, credentials, payload=None):
    """Send a JSON request without writing credentials to a file or argument list."""
    if not route.startswith("/v1/networks/") or any(char in route for char in "#\n\r"):
        raise SystemExit("Use a relative /v1/networks/ route.")
    options = ["url = " + json.dumps(base + route)]
    if key:
        options.append("header = " + json.dumps("x-api-key: " + key))
    if credentials:
        options.append('aws-sigv4 = "aws:amz:us-east-1:execute-api"')
        options.append("user = " + json.dumps(credentials["AccessKeyId"] + ":" + credentials["SecretAccessKey"]))
        options.append("header = " + json.dumps("x-amz-security-token: " + credentials["SessionToken"]))
    if payload is not None:
        options.append('header = "Content-Type: application/json"')
        options.append("data-binary = " + json.dumps(json.dumps(payload, ensure_ascii=True)))
    result = subprocess.run(
        ["curl", "--config", "-", "--request", method, "--silent", "--show-error",
         "--connect-timeout", "6", "--max-time", "34", "--write-out", "\n%{http_code}"],
        input="\n".join(options) + "\n", text=True, capture_output=True, check=True,
    )
    content, _, status = result.stdout.rpartition("\n")
    return int(status), content


def main():
    """Check deployment routing or invoke an explicitly selected operation."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("route", nargs="?")
    parser.add_argument("--method", choices=("GET", "POST"), default="GET")
    parser.add_argument("--body", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    base = os.environ["OGM_INVOKE_URL"].rstrip("/")
    key = os.environ["OGM_API_KEY"]
    if not re.fullmatch(r"https://[a-z0-9]+\.execute-api\.us-east-1\.amazonaws\.com/demo", base):
        parser.error("OGM_INVOKE_URL must be the guide's exact InvokeUrl output.")
    if not re.fullmatch(r"[A-Za-z0-9]{20,128}", key):
        parser.error("OGM_API_KEY must be the API key value.")
    try:
        credentials = access()
        if args.verify:
            labels = {"1": "SIMULATION", "2": "FEEDBACK", "3": "PERSONALIZATION"}
            for number, network in labels.items():
                code, body = request(base, f"/v1/networks/{number}/health", "GET", key, credentials)
                data = json.loads(body)
                if (
                    code != 200 or data.get("network") != network or data.get("role") != "ORCHESTRATOR"
                    or not data.get("agentsEnabled") or not data.get("modelConfigured")
                ):
                    raise SystemExit(f"Network {number} is not ready: HTTP {code} {body}")
                print(f"{number} {network}: HTTP {code}, model {data.get('model')}")
            probes = (
                ("unsupported network", "/v1/networks/4/health", key, credentials, 404),
                ("missing key", "/v1/networks/1/health", None, credentials, 403),
                ("unsigned request", "/v1/networks/1/health", key, None, 403),
                ("internal path", "/v1/networks/1/internal/outcomes/events", key, credentials, 404),
            )
            for name, route, probe_key, cred, expected in probes:
                code, content = request(base, route, "GET", probe_key, cred)
                if code != expected:
                    raise SystemExit(f"{name}: expected {expected}, got {code} {content}")
                print(f"{name}: HTTP {code}")
            return
        if not args.route:
            parser.error("Provide a route or use --verify.")
        if args.body is not None and args.method != "POST":
            parser.error("--body is for POST requests.")
        body = json.loads(args.body.read_text()) if args.body else ({} if args.method == "POST" else None)
        code, content = request(base, args.route, args.method, key, credentials, body)
        print(content)
        print(f"HTTP {code}", file=sys.stderr)
        sys.exit(0 if 200 <= code < 300 else 1)
    except subprocess.CalledProcessError as exc:
        # Curl's diagnostic is emitted without a credential-bearing option or header dump.
        raise SystemExit((exc.stderr or "CloudShell call failed").strip()) from exc


if __name__ == "__main__":
    main()
