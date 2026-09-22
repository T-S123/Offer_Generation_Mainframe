#!/usr/bin/env bash
# Starts the configured local Java services, waits for each API to respond and cleans up owned child
# processes, or invokes a selected batch command.
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
# Waits up to sixty seconds for a live service to answer an HTTP probe, allowing cold JVM responses up to ten seconds.
wait_for_api() {
    local name="$1" pid="$2" url="$3" token_file="$4" hint="$5"
    local deadline=$((SECONDS + 60)) remaining probe_timeout
    local -a auth=()
    while (( SECONDS < deadline )); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "$name failed to start. $hint" >&2
            return 1
        fi
        auth=()
        if [[ -n "$token_file" ]]; then
            if [[ ! -s "$token_file" ]]; then sleep 0.5; continue; fi
            auth=(-H "Authorization: Bearer $(cat "$token_file")")
        fi
        remaining=$((deadline - SECONDS))
        (( remaining > 0 )) || break
        probe_timeout=$((remaining < 10 ? remaining : 10))
        if curl --silent --fail --connect-timeout 2 --max-time "$probe_timeout" "${auth[@]}" "$url" >/dev/null; then
            if kill -0 "$pid" 2>/dev/null; then return 0; fi
        fi
        sleep 0.5
    done
    echo "$name startup timed out after 60 seconds. $hint" >&2
    return 1
}
wait_for_api 'Credit service' "$credit_pid" http://127.0.0.1:8091/api/v1/credit/health runtime/bureau-api-token 'See runtime/credit-service.log.'
java -Dengine.home="$PWD" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar services/target/customer-engine-1.0.0.jar &
engine_pid=$!
wait_for_api 'Engine' "$engine_pid" http://127.0.0.1:8090/api/v1/health '' 'Read the engine error above.'
java -Dengine.home="$PWD" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar marketing-service/target/marketing-offers-1.0.0.jar >runtime/marketing-service.log 2>&1 &
marketing_pid=$!
wait_for_api 'Marketing service' "$marketing_pid" http://127.0.0.1:8092/api/v1/health runtime/marketing-api-token 'See runtime/marketing-service.log.'
echo 'All three APIs are responding. Marketing offers: 127.0.0.1:8092'
echo 'Terminal: 01 Customer for an application; 02 Business for simulations.'
wait -n "$engine_pid" "$credit_pid" "$marketing_pid"
