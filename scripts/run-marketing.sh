#!/usr/bin/env bash
# Loads local configuration and starts the independent Java marketing offer service.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
set -a
source runtime/local.env
set +a
exec java -Dengine.home="$PWD" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar marketing-service/target/marketing-offers-1.0.0.jar
