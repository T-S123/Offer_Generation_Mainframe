"""Run tagged synthetic customers through the owner APIs and record cohort evidence."""
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import random
import time
from urllib.parse import quote

from importlib.util import module_from_spec, spec_from_file_location

_spec = spec_from_file_location("presenter_fixture", Path(__file__).with_name("seed-presenter-a2a.py"))
_fixture = module_from_spec(_spec)
_spec.loader.exec_module(_fixture)


def publications(api):
    today = datetime.now(timezone.utc).date().isoformat()
    active = {offer["id"]: offer["version"] for offer in api.get("business/catalog")["offers"]
              if offer["data"]["active"] and offer["data"]["startsOn"] <= today <= offer["data"]["endsOn"]}
    result = {}
    after = ""
    while True:
        page = api.get("ai/performance/publications?after=" + quote(after))
        for row in page:
            version = row["data"]["preview"]["receipt"].get("offerVersion", 1)
            if active.get(row["id"]) == version:
                result[row["id"]] = version
        if len(page) < 20:
            return result
        after = page[-1]["id"]


def bands(profile):
    income, debt = profile.get("monthlyIncomeUsd"), profile.get("monthlyDebtPaymentsUsd")
    utilization, score = profile.get("creditUtilizationPct"), profile.get("creditScore")
    return {
        "Debt-to-income": "Unavailable" if not income or debt is None else ("Above 35%" if debt / income > .35 else "Up to 35%"),
        "Credit utilization": "Unavailable" if utilization is None else ("Above 60%" if utilization > 60 else "Up to 60%"),
        "Credit score": "Unavailable" if score is None else ("Below 680" if score < 680 else "680 or above"),
    }


def outcome(api, request_id, customer, versions):
    profile = api.get("customers/" + customer)
    pipeline = profile.get("pipeline") or {}
    state = pipeline.get("state")
    if state not in {"COMPLETED", "OFFERS_PENDING", "NO_ELIGIBLE_OFFERS", "ATTENTION_REQUIRED", "STOPPED"}:
        return None
    dimensions = bands(profile["profiles"][0]["data"])
    if state in {"ATTENTION_REQUIRED", "STOPPED"}:
        return {"customer": customer, "resolved": False, "eligible": [], "selected": None, "bands": dimensions}
    offers, after = [], ""
    if state in {"COMPLETED", "OFFERS_PENDING"}:
        # Check all qualified admissions have finished bureau processing and their approved offers are ready.
        run = pipeline["runId"]
        approvals = set()
        offset = 0
        while True:
            page = api.get(f"marketing/runs/{run}/results?offset={offset}&limit=200")
            for qualification in page["items"]:
                if qualification["outcome"] != "QUALIFIED":
                    continue
                result = api.get("bureau/requests/AUTO-B-" + qualification["id"]).get("result")
                if not result:
                    return None
                if result["status"] == "FAILED":
                    return {"customer": customer, "resolved": False, "eligible": [], "selected": None, "bands": dimensions}
                if (result.get("decision") or {}).get("outcome") == "APPROVED":
                    approvals.add((qualification["riskId"], qualification["campaignId"], qualification["offerId"], qualification["offerVersion"]))
            offset += len(page["items"])
            if offset >= page["total"]:
                break
        while True:
            page = api.get(f"offer-creation/customers/{customer}/offers?limit=100&after={quote(after)}")
            offers.extend(page["items"])
            after = page.get("nextAfter")
            if not after:
                break
        ready = {(offer["riskId"], offer["campaignId"], offer["catalogOfferId"], offer["catalogOfferVersion"])
                 for offer in offers}
        if not offers or not approvals.issubset(ready):
            # An unavailable or expired offer set does not establish a decline.
            return None
    eligible = sorted({o["catalogOfferId"] for o in offers
                       if versions.get(o["catalogOfferId"]) == o["catalogOfferVersion"]})
    rng = random.Random(request_id + ":" + customer)
    # A seeded preference difference produces a demo association without prescribing any measured rate.
    pass_probability = .75 if dimensions["Debt-to-income"] == "Above 35%" or dimensions["Credit utilization"] == "Above 60%" else .32
    selected = None
    if offers and rng.random() >= pass_probability:
        offer = rng.choice(sorted(offers, key=lambda row: row["id"]))
        choice_id = "DEMO-" + hashlib.sha256((request_id + customer).encode()).hexdigest()[:60]
        receipt = api.call("POST", f"offer-creation/customers/{customer}/offers/{offer['id']}/selections", {
            "requestId": choice_id, "sourceVersion": offer["sourceVersion"], "kind": "ORIGINAL",
            "terms": offer["original"]["terms"],
        })
        if receipt["status"] != "SELECTED_DEMO_ONLY":
            raise RuntimeError("Offer owner did not record the synthetic selection.")
        selected = offer["catalogOfferId"]
    return {"customer": customer, "resolved": True, "eligible": eligible, "selected": selected, "bands": dimensions}


def aggregate(records, versions):
    resolved = [row for row in records if row["resolved"]]
    reports = []
    for offer_id, version in versions.items():
        cells = {}
        eligible = selected = 0
        for row in resolved:
            qualifies = offer_id in row["eligible"]
            chose = qualifies and row["selected"] == offer_id
            eligible += qualifies
            selected += chose
            for dimension, band in row["bands"].items():
                counts = cells.setdefault((dimension, band), [0, 0, 0])
                counts[0] += 1
                counts[1] += qualifies
                counts[2] += chose
        reports.append({"publicationId": offer_id, "offerVersion": version, "assessed": len(resolved),
                        "eligible": eligible, "selected": selected,
                        "segments": [{"dimension": k[0], "band": k[1], "assessed": n[0], "eligible": n[1], "selected": n[2]}
                                     for k, n in sorted(cells.items()) if n[0] >= 10]})
    return reports


def simulate(root, request_id, progress):
    api = _fixture.LocalEngine(root, 8090)
    api.get("ai/health")
    folder = root / "runtime/presenter/requests"
    plan_path = folder / (request_id + ".cohort-plan.json")
    ledger = folder / (request_id + ".cohort-ledger.jsonl")
    if not plan_path.exists():
        versions = publications(api)
        if not versions:
            raise RuntimeError("Publish an AI-designed offer before generating the comparison cohort.")
        temp = plan_path.with_suffix(".tmp")
        temp.write_text(json.dumps(versions), encoding="utf-8")
        temp.replace(plan_path)
    versions = json.loads(plan_path.read_text(encoding="utf-8"))
    progress("Generating 10,000 tagged synthetic customer profiles…")
    job = api.call("POST", "demo-cohorts", {"requestId": request_id})
    deadline = time.monotonic() + 90 * 60
    while job["status"] != "COMPLETED":
        if job["status"] == "FAILED" or time.monotonic() >= deadline:
            raise RuntimeError("Synthetic generation did not complete. Check the base worker and retry this cohort.")
        time.sleep(3)
        job = api.get("simulations/" + job["id"])
    customer_ids = []
    while len(customer_ids) < job["count"]:
        page = api.get(f"customers?batchId={job['id']}&offset={len(customer_ids)}&limit=200")
        if not page["items"]:
            raise RuntimeError("The tagged customer list is incomplete.")
        customer_ids.extend(row["id"] for row in page["items"])
    records = {}
    if ledger.exists():
        saved_rows = ledger.read_bytes()
        # A process interrupted during append may leave one incomplete line.
        if saved_rows and not saved_rows.endswith(b"\n"):
            saved_rows = saved_rows[:saved_rows.rfind(b"\n") + 1]
            ledger.write_bytes(saved_rows)
        for line in saved_rows.splitlines():
            row = json.loads(line)
            records[row["customer"]] = row
    pending = [customer for customer in customer_ids if customer not in records]
    with ledger.open("a", encoding="utf-8") as saved, ThreadPoolExecutor(max_workers=6) as pool:
        while pending:
            if time.monotonic() >= deadline:
                raise RuntimeError(f"{len(records):,} of 10,000 profiles have results. Retry to continue this cohort.")
            deferred = []
            # Bound batches so completed results reach the ledger throughout processing.
            for start in range(0, len(pending), 200):
                batch = pending[start:start + 200]
                def read(customer):
                    try:
                        return outcome(api, request_id, customer, versions)
                    except (RuntimeError, OSError):
                        return None
                for customer, row in zip(batch, pool.map(read, batch)):
                    if row is None:
                        deferred.append(customer)
                        continue
                    saved.write(json.dumps(row) + "\n")
                    records[customer] = row
                saved.flush()
                progress(f"Recording qualification and selections · {len(records):,} / 10,000 profiles processed")
            pending = deferred
            if pending:
                time.sleep(4)
    active = publications(api)
    if any(active.get(offer) != version for offer, version in versions.items()):
        raise RuntimeError("A measured catalog version changed during the run. The tagged profiles remain available.")
    cohort = {"cohortId": job["id"], "createdAt": job["createdAt"], "size": job["count"],
              "unresolved": sum(not row["resolved"] for row in records.values()),
              "offers": aggregate(records.values(), versions)}
    api.call("POST", "ai/outcomes/demo-cohorts", cohort)
    return {key: cohort[key] for key in ("cohortId", "size", "unresolved")}
