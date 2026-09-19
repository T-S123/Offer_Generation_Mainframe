#!/usr/bin/env bash
# Enables the configured PostgreSQL integration tests and invokes the complete COBOL and Java build.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source runtime/local.env
set +a
export BUREAU_TEST_DATABASE_URL="$DATABASE_URL"
bash scripts/build.sh
