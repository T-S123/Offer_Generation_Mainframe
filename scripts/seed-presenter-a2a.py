#!/usr/bin/env python3
"""Prepare the unpublished demo through owner APIs and record the exact customer, draft and catalog identities."""

import argparse
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib import error, request


CUSTOMER_REFERENCE = "A2A-FILM-001"
CUSTOMER_NAME = "A2A Recording Customer"
DRAFT_NAME = "A2A filming personal loan"
BASELINE = "DEMO-PL-1"
CAMPAIGN = "FILM-PL-20261104"
DISCOVERY_SEED = 20261105
VALIDATION_SEED = 20261104

# Pin the reviewed fixture text, accepting the checkout's LF or CRLF convention.
# The Research API separately checks the exact hash of the installed file at approval.
POLICY_TEXT_HASHES = {
    "a2a-personal-loan-policy.md": "4fa0a39093adf36715a08932c65fea54f151eb49dc5e204a1e2550a6fc4e9b58",
    "a2a-simulation-operating-policy.md": "976860e7c395e5a1cbd85a9fc64132caf5af506fb13892c040cd1e6496a5f40e",
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(content):
    return hashlib.sha256(content).hexdigest()


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalEngine:
    def __init__(self, root, port):
        require(1 <= port <= 65535, "Invalid local engine port.")
        self.base = f"http://127.0.0.1:{port}/api/v1/"
        self.token = (root / "runtime/api-token").read_text().strip()
        self.http = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        data = None if body is None else json.dumps(body).encode()
        req = request.Request(self.base + path, method=method, data=data, headers=headers)
        try:
            with self.http.open(req, timeout=120) as response:
                return json.load(response)
        except error.HTTPError as failure:
            raise RuntimeError(f"{method} {path} returned HTTP {failure.code}. Check the local service logs.") from failure

    def get(self, path):
        return self.call("GET", path)


def ensure_empty(api):
    counts = api.get("customers?limit=1")
    require(counts["total"] == 0, "Demo A2A requires a reset local database. Customers are already present.")
    for path in ("business/drafts", "business/populations", "business/runs", "business/publications",
                 "ai/workflows?limit=1", "ai/performance/publications"):
        require(api.get(path) == [], f"Existing data at {path}. Start Demo A2A again from the presenter controls.")
    health = api.get("ai/health")
    require(health.get("agentsEnabled") and health.get("modelConfigured"),
            "Start all A2A networks with the configured local provider key before preparing the demo.")


def install_policies(root, folder):
    """Reuse matching local documents or add an exact copy under a separate presenter name."""
    folder = folder.resolve()
    installed = {}
    for name, text_hash in POLICY_TEXT_HASHES.items():
        content = (root / "app/data/policies" / name).read_bytes()
        require(digest(content.replace(b"\r\n", b"\n")) == text_hash,
                f"The bundled {name} differs from the reviewed demo fixture.")
        expected = digest(content)
        original = folder / name
        if original.is_file() and not original.is_symlink() and digest(original.read_bytes()) == expected:
            installed[name] = expected
            continue

        # Custom policy directories outside the demo may be read; never modify them.
        require(folder.is_relative_to(root) and folder != root,
                f"The configured external AI policy folder must contain the exact bundled {name}.")
        presenter = folder / "presenter"
        require(not presenter.is_symlink(), "The presenter policy folder must be a real directory.")
        presenter.mkdir(parents=True, exist_ok=True)
        target = presenter / name
        require(not target.is_symlink(), f"The presenter fixture {name} must be a regular file.")
        if target.exists():
            require(target.is_file() and digest(target.read_bytes()) == expected,
                    f"The dedicated presenter copy of {name} was edited. Restore the bundled copy and retry.")
        else:
            with target.open("xb") as dest:
                dest.write(content)
        installed["presenter/" + name] = expected
    return installed


def approve_policies(api, installed, expires):
    documents = api.call("POST", "ai/policies/refresh", {})["documents"]
    indexed = {doc["name"]: doc for doc in documents if not doc["removed"]}
    for name, expected in installed.items():
        doc = indexed.get(name)
        require(doc and doc["hash"] == expected and doc["extractionStatus"] == "READY",
                f"Research must index the exact presenter fixture {name}. Check AI_POLICY_DIR and retry.")
        result = api.call("POST", f"ai/policies/{doc['id']}/approve", {
            "hash": expected, "product": "PERSONAL_LOAN", "geography": "US", "expiresOn": expires,
        })
        require(result["approved"] and result["hash"] == expected, f"Research did not approve {name}.")


def prepare_draft(api, baseline):
    campaign = {
        "name": "A2A recording personal-loan campaign", "active": True,
        "startsOn": baseline["startsOn"], "endsOn": baseline["endsOn"], "priority": 5,
        "capacity": 1000, "minimumTenureMonths": 12, "excludeExistingProduct": False, "offerIds": [BASELINE],
    }
    api.call("PUT", "marketing/campaigns/" + CAMPAIGN, {"expectedVersion": 0, "campaign": campaign})
    draft = api.call("POST", "business/drafts", {
        "name": DRAFT_NAME, "baselineOfferId": BASELINE, "campaignId": CAMPAIGN,
    }, key="presenter-filming-draft")
    # Keep every unedited field in the owner-supplied versioned default rule set.
    rules = draft["rules"]
    rules["bureau"]["minimumScore"] = 720
    rules["underwriting"]["minimumIncomeUsd"] = 3000
    offer = dict(draft["offer"], name=DRAFT_NAME, illustrativeAprPct=9.5)
    saved = api.call("PUT", "business/drafts/" + draft["id"], {
        "expectedVersion": draft["version"], "name": DRAFT_NAME, "offer": offer, "rules": rules,
    })
    require(saved["version"] == 2 and saved["campaign"]["id"] == CAMPAIGN, "The recording draft is not ready.")
    return saved


def prepare_customer(api):
    profile = {
        "externalReference": CUSTOMER_REFERENCE, "displayName": CUSTOMER_NAME, "monthlyIncomeUsd": 8200,
        "monthlyDebtPaymentsUsd": 650, "creditScore": 745, "delinquencies12m": 0, "creditUtilizationPct": 25,
        "personalLoanAmountUsd": 12000, "requestedCardLimitUsd": None, "autoLoanAmountUsd": None,
        "vehicleValueUsd": None, "vehicleAgeYears": None, "bankingTenureMonths": 48, "depositBalanceUsd": 8500,
        "monthlySpendUsd": 1700, "existingProducts": ["CHECKING"], "marketingOptIn": False,
        "prescreenOptOut": False, "preferredChannels": ["EMAIL"],
    }
    customer_id = api.call("POST", "customers", profile)["id"]
    bureau = api.call("PUT", "bureau/profiles/" + customer_id, {
        "expectedVersion": 0, "source": "LOCAL_IMPORT", "facts": {
            "fileStatus": "MATCHED", "creditScore": 775, "monthlyDebtUsd": 700, "utilizationPct": 18,
            "delinquencies12m": 0, "inquiries6m": 2, "oldestAccountMonths": 156, "bankruptcy": False,
            "reportedAt": datetime.now(timezone.utc).isoformat(),
        },
    })
    profile.update(monthlyIncomeUsd=9000, marketingOptIn=True)
    api.call("PUT", "customers/" + customer_id, {"expectedVersion": 1, "customer": profile})
    return customer_id, bureau["version"]


def population(api, seed):
    cohort = api.call("POST", "business/populations", {"count": 10000, "seed": seed},
                      key=f"presenter-cohort-{seed}")
    require(cohort["count"] == 10000 and cohort["seed"] == seed, "The saved population differs from the fixture.")
    return cohort


def verify_customer(api, customer_id):
    deadline = time.monotonic() + 150
    state = ""
    while time.monotonic() < deadline:
        customer = api.get("customers/" + customer_id)
        profile = max(customer["profiles"], key=lambda p: p["version"])
        require(profile["version"] == 2 and profile["data"]["monthlyIncomeUsd"] == 9000,
                "The prepared customer's saved profile changed during setup.")
        state = (customer.get("pipeline") or {}).get("state", "PENDING")
        if state in ("ATTENTION_REQUIRED", "NO_ELIGIBLE_OFFERS"):
            raise RuntimeError(f"The recording customer reached {state}. Check bureau and marketing readiness.")
        if state == "COMPLETED":
            ready = any(o["qualification"]["catalogOfferId"] == BASELINE and o.get("preScreen")
                        and not o.get("selection") for o in customer["offerView"]["items"])
            if ready:
                return
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for the recording customer's current baseline offer (pipeline: {state}).")


def seed(root, port):
    api = LocalEngine(root, port)
    ensure_empty(api)
    baseline = api.get("marketing/offers/" + BASELINE)["data"]
    today = datetime.now(timezone.utc).date().isoformat()
    require(baseline["active"] and baseline["startsOn"] <= today <= baseline["endsOn"],
            "The default personal-loan catalog offer must be active today.")
    installed = install_policies(root, Path(os.environ.get("AI_POLICY_DIR", root / "policy-documents")))
    approve_policies(api, installed, baseline["endsOn"])
    draft = prepare_draft(api, baseline)
    customer_id, bureau_version = prepare_customer(api)
    discovery = population(api, DISCOVERY_SEED)
    validation = population(api, VALIDATION_SEED)
    require(discovery["id"] != validation["id"], "Discovery and validation must be distinct populations.")
    verify_customer(api, customer_id)
    require(api.get(f"ai/customer-personalization/{customer_id}?profileVersion=2")["state"] == "NOT_STARTED",
            "Customer personalization has already started for this profile.")
    require(not api.get("business/publications") and not api.get("ai/workflows?limit=1"),
            "Demo A2A must start before the first experiment and publication.")
    manifest = {
        "state": "READY", "customerId": customer_id, "customerName": CUSTOMER_NAME,
        "catalogOfferId": BASELINE, "catalogOfferName": baseline["name"],
        "customerReference": CUSTOMER_REFERENCE, "profileVersion": 2, "initialIncome": 9000, "filmIncome": 9200,
        "bureauVersion": bureau_version, "campaignId": CAMPAIGN,
        "draftName": DRAFT_NAME, "draftId": draft["id"], "draftVersion": draft["version"],
        "discoverySeed": DISCOVERY_SEED, "discoveryPopulation": discovery, "validationPopulation": validation,
    }
    manifest_dir = root / "runtime/maintenance"
    manifest_dir.mkdir(parents=True, exist_ok=True)
    temporary = manifest_dir / "a2a-filming-start.json.tmp"
    temporary.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    temporary.replace(manifest_dir / "a2a-filming-start.json")
    print(f"Prepared {CUSTOMER_REFERENCE} ({customer_id}), {DRAFT_NAME} v2, and both unused cohorts.", flush=True)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--engine-port", type=int, default=8090)
    args = cli.parse_args()
    try:
        seed(Path.cwd().resolve(), args.engine_port)
    except (RuntimeError, OSError, KeyError, ValueError) as problem:
        sys.exit(f"A2A fixture setup failed: {problem}")
