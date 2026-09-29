#!/usr/bin/env bash
# Packages the AI runtime and runs isolated model-double, JDBC, document and A2A integration tests.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -f ai-service/pom.xml package
