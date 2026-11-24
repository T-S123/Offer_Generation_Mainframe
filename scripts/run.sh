#!/usr/bin/env bash
# Starts one bank's local service group, verifies its listeners and API readiness, and cleans up its
# children. The presenter supervisor pauses normal launches for resets; batch modes use existing APIs.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
source scripts/bank-env.sh
engine_options=(-Dengine.home="$PWD" "-Dengine.api.port=$ENGINE_API_PORT" "-Dbureau.credit.port=$ENGINE_CREDIT_PORT"
    "-Dmarketing.api.port=$ENGINE_MARKETING_PORT" "-Dengine.terminal.port=$ENGINE_TERMINAL_PORT")
if [[ $# -gt 0 ]]; then
    exec java "${engine_options[@]}" -jar services/target/customer-engine-1.0.0.jar "$@"
fi
if [[ "${PRESENTER_GROUP:-}" != base ]]; then exec python3 scripts/presenter-group.py base bash scripts/run.sh; fi
source scripts/startup-guard.sh
ports=("$ENGINE_API_PORT" "$ENGINE_TERMINAL_PORT")
[[ -z "${DATABASE_URL:-}" ]] || ports+=("$ENGINE_CREDIT_PORT" "$ENGINE_MARKETING_PORT")
guard_startup 'Base application' "$ENGINE_RUNTIME/base-launcher.lock" "${ports[@]}"
if [[ -z "${DATABASE_URL:-}" ]]; then
    exec java "${engine_options[@]}" -jar services/target/customer-engine-1.0.0.jar
fi
mkdir -p "$ENGINE_RUNTIME"
java "${engine_options[@]}" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar services/target/customer-engine-1.0.0.jar --credit-service >"$ENGINE_RUNTIME/credit-service.log" 2>&1 &
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
# Waits up to sixty seconds for this child to own its port and answer HTTP, allowing ten-second cold probes.
wait_for_api() {
    local name="$1" pid="$2" url="$3" token_file="$4" hint="$5" port="$6"
    local deadline=$((SECONDS + 60)) remaining probe_timeout
    local -a auth=()
    while (( SECONDS < deadline )); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "$name failed to start. $hint" >&2
            return 1
        fi
        if ! owns_listener "$pid" "$port"; then sleep 0.5; continue; fi
        auth=()
        if [[ -n "$token_file" ]]; then
            if [[ ! -s "$token_file" ]]; then sleep 0.5; continue; fi
            auth=(-H "Authorization: Bearer $(cat "$token_file")")
        fi
        remaining=$((deadline - SECONDS))
        (( remaining > 0 )) || break
        probe_timeout=$((remaining < 10 ? remaining : 10))
        if curl --silent --fail --connect-timeout 2 --max-time "$probe_timeout" "${auth[@]}" "$url" >/dev/null; then
            if owns_listener "$pid" "$port"; then return 0; fi
        fi
        sleep 0.5
    done
    echo "$name startup timed out after 60 seconds. $hint" >&2
    return 1
}
wait_for_api 'Credit service' "$credit_pid" "http://127.0.0.1:$ENGINE_CREDIT_PORT/api/v1/credit/health" "$ENGINE_RUNTIME/bureau-api-token" 'See the credit-service log.' "$ENGINE_CREDIT_PORT"
java "${engine_options[@]}" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar services/target/customer-engine-1.0.0.jar &
engine_pid=$!
wait_for_api 'Engine' "$engine_pid" "http://127.0.0.1:$ENGINE_API_PORT/api/v1/health" '' 'Read the engine error above.' "$ENGINE_API_PORT"
java "${engine_options[@]}" -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar marketing-service/target/marketing-offers-1.0.0.jar >"$ENGINE_RUNTIME/marketing-service.log" 2>&1 &
marketing_pid=$!
wait_for_api 'Marketing service' "$marketing_pid" "http://127.0.0.1:$ENGINE_MARKETING_PORT/api/v1/health" "$ENGINE_RUNTIME/marketing-api-token" 'See the marketing-service log.' "$ENGINE_MARKETING_PORT"
owns_listener "$engine_pid" "$ENGINE_TERMINAL_PORT" || { echo 'Engine terminal listener is not ready.' >&2; exit 1; }
for entry in "$credit_pid:$ENGINE_CREDIT_PORT" "$engine_pid:$ENGINE_API_PORT" "$marketing_pid:$ENGINE_MARKETING_PORT"; do
    owns_listener "${entry%:*}" "${entry#*:}" || { echo 'A base service exited during startup.' >&2; exit 1; }
done

if [[ "$LENDING_BANK" == anna ]]; then python3 -B scripts/seed-anna.py; fi
echo "All three APIs are responding. Marketing offers: 127.0.0.1:$ENGINE_MARKETING_PORT"
echo 'Terminal: select your bank, then 01 Customer or 02 Business.'
wait -n "$engine_pid" "$credit_pid" "$marketing_pid"
