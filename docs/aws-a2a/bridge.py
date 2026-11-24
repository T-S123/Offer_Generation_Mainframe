"""Expose a network's reviewed REST routes to the internal load balancer."""

import http.client
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MAX_REQUEST = 1_048_576
MAX_RESPONSE = 4_194_304
REF = r"[A-Za-z0-9_.:-]{1,160}"
API = "/api/v1/ai"
ROLES = {
    "1": (8100, "SIMULATION", ((0, "ORCHESTRATOR"), (1, "RESEARCH"), (2, "DESIGNER"),
                                (3, "COORDINATOR"), (4, "ANALYZER"), (5, "REFLECTION"))),
    "2": (8110, "FEEDBACK", ((0, "ORCHESTRATOR"), (1, "RESEARCH"), (4, "ANALYZER"),
                              (5, "REFLECTION"), (6, "ELIGIBILITY"))),
    "3": (8120, "PERSONALIZATION", ((0, "ORCHESTRATOR"), (2, "DESIGNER"),
                                    (6, "ELIGIBILITY"), (7, "CONTEXT"), (8, "GUIDE"))),
}
GET_SIMULATION = rf"/workflows(?:/{REF}(?:/evaluations|/artifacts/{REF})?)?"
POST_SIMULATION = rf"/workflows(?:/{REF}/(?:messages|scope-confirmations|cancel|resume|publication-previews))?"
GET_FEEDBACK = rf"/workflows(?:/{REF}(?:/artifacts/{REF})?)?"
POST_FEEDBACK = rf"/workflows/{REF}/(?:messages|resume|cancel)"
GET_CUSTOMER = rf"/customers/{REF}(?:/workflows/{REF})?"
POST_CUSTOMER = rf"/customers/{REF}(?:/workflows/{REF}/(?:messages|cancel))?"


def destination(network, method, target):
    """Translate only the configured network's recognized external routes."""
    parsed = urlsplit(target)
    if (
        parsed.scheme or parsed.netloc or parsed.fragment
        or len(parsed.query) > 4096 or ".." in parsed.path
    ):
        return None
    match = re.fullmatch(r"(?:/demo)?/v1/networks/([123])(/.+)", parsed.path)
    if not match or match[1] != network:
        return None
    suffix = match[2]
    prefix = None
    if method == "GET" and suffix == "/health":
        prefix = API + suffix
    elif network == "1":
        if method == "GET" and suffix == "/defaults":
            prefix = API + suffix
        elif (
            (method == "GET" and re.fullmatch(GET_SIMULATION, suffix))
            or (method == "POST" and re.fullmatch(POST_SIMULATION, suffix))
        ):
            prefix = API + "/simulation" + suffix
        elif method == "POST" and re.fullmatch(rf"/publication-previews/{REF}/confirm", suffix):
            prefix = API + suffix
    elif network == "2":
        if method == "GET" and suffix in ("/defaults", "/publications"):
            prefix = API + ("/feedback/defaults" if suffix == "/defaults" else suffix)
        elif method == "POST" and suffix in ("/investigations", "/briefings"):
            prefix = API + "/feedback" + suffix
        elif (
            (method == "GET" and re.fullmatch(GET_FEEDBACK, suffix))
            or (method == "POST" and re.fullmatch(POST_FEEDBACK, suffix))
        ):
            prefix = API + "/feedback" + suffix
        elif method == "POST" and re.fullmatch(
            rf"/performance/publications/{REF}/(?:measurements|eligibility)", suffix
        ):
            prefix = API + suffix
    elif (
        (method == "GET" and re.fullmatch(GET_CUSTOMER, suffix))
        or (method == "POST" and re.fullmatch(POST_CUSTOMER, suffix))
    ):
        prefix = API + "/customer-personalization/" + suffix.removeprefix(
            "/customers/"
        ).replace("/workflows/", "/", 1)
    if prefix is None:
        return None
    return prefix + ("?" + parsed.query if parsed.query else "")


def upstream(port, token, method, path, body=None, timeout=23):
    """Open a bounded loopback request using the bank's server-side credential."""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        conn.request(method, path, body=body, headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token,
        })
        response = conn.getresponse()
        content = response.read(MAX_RESPONSE + 1)
        if len(content) > MAX_RESPONSE:
            raise ValueError("response limit")
        return response.status, content
    finally:
        conn.close()


def handler_for(network):
    """Bind the bridge to one network's role and credential files."""
    base, name, roster = ROLES[network]
    folder = Path("runtime/ai") / ("" if network == "1" else name.lower())
    analyst = (folder / "analyst-token").read_text().strip()
    orchestrator = json.loads((folder / "credentials.json").read_text())["ORCHESTRATOR"]

    class Bridge(BaseHTTPRequestHandler):
        """Serve health probes and authenticated loopback REST forwarding."""

        def log_message(self, *_):
            # Queries may include session IDs. Log neither the URL nor headers.
            return

        def send_json(self, status, data):
            payload = data if isinstance(data, bytes) else json.dumps(data).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def ready(self):
            for offset, role in roster:
                status, body = upstream(base + offset, orchestrator, "GET", API + "/health", timeout=1)
                health = json.loads(body)
                if status != 200 or health.get("role") != role or health.get("network") != name:
                    return False
            return True

        def serve(self):
            if self.command == "GET" and self.path == "/readyz":
                try:
                    ready = self.ready()
                except (OSError, ValueError, http.client.HTTPException):
                    ready = False
                return self.send_json(200 if ready else 503, {"ready": ready})
            try:
                target = destination(network, self.command, self.path)
            except ValueError:
                target = None
            if target is None:
                return self.send_json(404, {"code": "ROUTE_NOT_FOUND"})
            body = None
            if self.command == "POST":
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    return self.send_json(400, {"code": "INVALID_LENGTH"})
                if self.headers.get("Transfer-Encoding") or not 0 <= length <= MAX_REQUEST:
                    return self.send_json(413, {"code": "REQUEST_LIMIT"})
                try:
                    self.connection.settimeout(5)
                    body = self.rfile.read(length) if length else b"{}"
                    if len(body) != (length or 2) or not isinstance(json.loads(body), dict):
                        return self.send_json(400, {"code": "JSON_OBJECT_REQUIRED"})
                except (ValueError, TimeoutError):
                    return self.send_json(400, {"code": "JSON_OBJECT_REQUIRED"})
            try:
                status, payload = upstream(base, analyst, self.command, target, body)
                self.send_json(status, payload)
            except TimeoutError:
                self.send_json(504, {"code": "OWNER_TIMEOUT"})
            except (OSError, ValueError, http.client.HTTPException):
                self.send_json(502, {"code": "NETWORK_UNAVAILABLE"})

        do_GET = serve
        do_POST = serve

    return Bridge


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), handler_for(os.environ["NETWORK_ID"])).serve_forever()
