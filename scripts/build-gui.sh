#!/usr/bin/env bash
# Linux counterpart: checks browser, presenter maintenance, gateway and launcher boundaries, then packages with Java 17.
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm ci && npm run contract:check && npm test && npm run build)
python3 scripts/test-presenter.py
mvn -q -f services/pom.xml -Dtest=WebServerTest,StartupLauncherTest package

