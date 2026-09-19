#!/usr/bin/env bash
# Starts the configured local Java services or invokes a selected batch command and cleans up owned child
# processes.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ -f runtime/local.env ]]; then
    set -a
    source runtime/local.env
    set +a
fi
if [[ $# -gt 0 ]]; then
    exec java -Dengine.home="$PWD" -jar services/target/customer-engine-1.0.0.jar "$@"
fi
if [[ -z "${DATABASE_URL:-}" ]]; then
    exec java -Dengine.home="$PWD" -jar services/target/customer-engine-1.0.0.jar
fi
mkdir -p runtime
java -Dengine.home="$PWD" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar services/target/customer-engine-1.0.0.jar --credit-service >runtime/credit-service.log 2>&1 &
credit_pid=$!
engine_pid=
marketing_pid=
# Stops the Java child processes owned by this launcher and waits for their termination.
cleanup() {
    trap - EXIT INT TERM
    [[ -z "$engine_pid" ]] || kill "$engine_pid" 2>/dev/null || true
    [[ -z "$marketing_pid" ]] || kill "$marketing_pid" 2>/dev/null || true
    kill "$credit_pid" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for attempt in {1..40}; do
    if ! kill -0 "$credit_pid" 2>/dev/null; then
        echo 'Credit service failed to start. See runtime/credit-service.log.' >&2
        exit 1
    fi
    if [[ -f runtime/bureau-api-token ]] && curl --silent --fail --max-time 1 -H "Authorization: Bearer $(cat runtime/bureau-api-token)" http://127.0.0.1:8091/api/v1/credit/health >/dev/null; then break; fi
    sleep 0.5
done
if [[ $attempt == 40 ]]; then echo 'Credit service startup timed out.' >&2; exit 1; fi
java -Dengine.home="$PWD" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar services/target/customer-engine-1.0.0.jar &
engine_pid=$!
for attempt in {1..40}; do
    if ! kill -0 "$engine_pid" 2>/dev/null; then echo 'Engine failed to start.' >&2; exit 1; fi
    if [[ -f runtime/marketing-source-token && -f runtime/marketing-api-token ]] && curl --silent --fail --max-time 1 http://127.0.0.1:8090/api/v1/health >/dev/null; then break; fi
    sleep 0.5
done
if [[ $attempt == 40 ]]; then echo 'Engine startup timed out.' >&2; exit 1; fi
java -Dengine.home="$PWD" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar marketing-service/target/marketing-offers-1.0.0.jar >runtime/marketing-service.log 2>&1 &
marketing_pid=$!
echo 'Marketing offers: 127.0.0.1:8092 | Terminal: main menu 9, then 7'
wait -n "$engine_pid" "$credit_pid" "$marketing_pid"
