#!/usr/bin/env bash
# Runs the loopback browser adapter and supplies the local configuration for presenter maintenance.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f runtime/local.env ]]; then set -a; source <(sed 's/\r$//' runtime/local.env); set +a; fi
base_db="${DATABASE_URL:-}"; base_broker="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
if [[ -f runtime/ai.env ]]; then set -a; source <(sed 's/\r$//' runtime/ai.env); set +a; fi
export AI_DATABASE_URL="${AI_DATABASE_URL:-${DATABASE_URL:-}}" DATABASE_URL="$base_db" KAFKA_BOOTSTRAP_SERVERS="$base_broker"
port="${1:-8080}"
[[ "$port" =~ ^[0-9]+$ ]] && ((port >= 1024 && port <= 65535)) || { echo 'Use a port from 1024 to 65535.' >&2; exit 1; }
[[ -s runtime/api-token && -f frontend/dist/index.html && -f services/target/customer-engine-1.0.0.jar ]] || {
    echo 'Build the GUI and start the base engine first. See README.md.' >&2; exit 1;
}
[[ -e runtime/presenter/maintenance ]] || curl --silent --fail --max-time 5 http://127.0.0.1:8090/api/v1/health >/dev/null || {
    echo 'The base engine is unavailable. Start scripts/run.ps1 first.' >&2; exit 1;
}
exec java -Djava.net.preferIPv4Stack=true -Dengine.home="$PWD" -Dgui.port="$port" -cp services/target/customer-engine-1.0.0.jar com.lending.engine.web.WebServer

