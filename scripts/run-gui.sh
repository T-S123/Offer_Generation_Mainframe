#!/usr/bin/env bash
# Runs a browser adapter for both banks; each Presenter action loads its bank's own configuration.
set -euo pipefail
cd "$(dirname "$0")/.."
port="${1:-8080}"
[[ "$port" =~ ^[0-9]+$ ]] && ((port >= 1024 && port <= 65535)) || { echo 'Use a port from 1024 to 65535.' >&2; exit 1; }
[[ -f frontend/dist/index.html && -f services/target/customer-engine-1.0.0.jar ]] || {
    echo 'Build the GUI first. See README.md.' >&2; exit 1;
}
exec java -Djava.net.preferIPv4Stack=true -Dengine.home="$PWD" -Dgui.port="$port" -cp services/target/customer-engine-1.0.0.jar com.lending.engine.web.WebServer

