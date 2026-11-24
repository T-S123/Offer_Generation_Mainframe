#!/usr/bin/env python3
"""Create Anna's starter profiles and policy inputs once through its owner API."""
import json
import os
from pathlib import Path
import shutil
from urllib import error, request

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "runtime/anna"
PROFILES = (
    ("ANNA-DEMO-001", "Sofia Ivanova", 9100, 640, 759),
    ("ANNA-DEMO-002", "Nikolai Levin", 7300, 820, 731),
    ("ANNA-DEMO-003", "Darya Oblonsky", 6650, 460, 718),
)


def seed():
    if os.environ.get("LENDING_BANK") != "anna":
        raise RuntimeError("Starter profiles must target Anna.")
    marker = RUNTIME / "starter-profiles-ready"
    if marker.exists():
        return
    token = (RUNTIME / "api-token").read_text().strip()
    opener = request.build_opener(request.ProxyHandler({}))

    def call(path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = request.Request("http://127.0.0.1:8290/api/v1/" + path, data=data,
                              headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with opener.open(req, timeout=25) as response:
            return json.load(response)

    for reference, name, income, debt, score in PROFILES:
        try:
            call("customers/resolve?reference=" + reference)
        except error.HTTPError as failure:
            if failure.code != 404:
                raise
        else:
            continue
        call("customers", {
            "externalReference": reference, "displayName": name,
            "monthlyIncomeUsd": income, "monthlyDebtPaymentsUsd": debt, "creditScore": score,
            "delinquencies12m": 0, "creditUtilizationPct": 20,
            "personalLoanAmountUsd": 10000, "bankingTenureMonths": 36, "depositBalanceUsd": 6500,
            "monthlySpendUsd": 1450, "existingProducts": ["CHECKING"],
            "marketingOptIn": True, "prescreenOptOut": False, "preferredChannels": ["EMAIL"],
        })
    policies = RUNTIME / "policy-documents"
    policies.mkdir(exist_ok=True)
    for source in sorted((ROOT / "app/data/policies").glob("*.md")):
        destination = policies / source.name
        if not destination.exists():
            shutil.copyfile(source, destination)
    marker.touch()
    print("Anna's three synthetic profiles are ready.")


if __name__ == "__main__":
    seed()
