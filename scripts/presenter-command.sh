#!/usr/bin/env bash
# Loads the selected bank's private configuration for its existing Presenter coordinator.
set -euo pipefail
cd "$(dirname "$0")/.."
export LENDING_BANK="$1"
shift
source scripts/bank-env.sh ai
exec python3 -B scripts/presenter-reset.py "$@"
