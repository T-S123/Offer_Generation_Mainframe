#!/usr/bin/env bash
# Starts one six-agent group with Windows-compatible configuration and owned process cleanup.
# Rejects occupied ports/duplicate launchers and verifies each child listener before advertising readiness.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ -f runtime/local.env ]]; then set -a; source <(sed 's/\r$//' runtime/local.env); set +a; fi
if [[ -f runtime/ai.env ]]; then set -a; source <(sed 's/\r$//' runtime/ai.env); set +a; fi
jar=ai-service/target/ai-simulation-1.0.0.jar
[[ -f "$jar" ]] || { echo 'Build AI first: scripts/build-ai.ps1' >&2; exit 1; }
if [[ "${1:-}" == "--init" ]]; then exec java -Dengine.home="$PWD" -jar "$jar" --init; fi
if [[ "${1:-}" == "--check" ]]; then exec java -Dengine.home="$PWD" -jar "$jar" --check; fi
[[ $# -eq 0 ]] || { echo 'Use --init, --check, or no arguments.' >&2; exit 1; }
[[ -n "${AI_DATABASE_URL:-${DATABASE_URL:-}}" ]] || { echo 'Configure AI_DATABASE_URL or DATABASE_URL in runtime/ai.env.' >&2; exit 1; }
[[ -n "${OPENAI_API_KEY:-}" ]] || { echo 'Configure OPENAI_API_KEY in the local environment or runtime/ai.env.' >&2; exit 1; }
[[ -s runtime/api-token && -s runtime/marketing-api-token ]] || { echo 'Start the existing engine services first.' >&2; exit 1; }
source scripts/startup-guard.sh
base_port="${AI_BASE_PORT:-8100}"
[[ "$base_port" =~ ^[0-9]{1,5}$ ]] && (( 10#$base_port >= 1 && 10#$base_port <= 65530 )) || {
    echo 'AI_BASE_PORT must be an integer from 1 to 65530.' >&2; exit 1;
}
base_port=$((10#$base_port))
ports=()
for ((i=0; i<6; i++)); do ports+=("$((base_port + i))"); done
guard_startup 'AI services' runtime/ai-launcher.lock "${ports[@]}"
java -Dengine.home="$PWD" -jar "$jar" --init
pids=()
# Stops only the Java agents started by this launcher, releasing its lock after their exit.
cleanup() { trap - EXIT INT TERM; for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for role in ORCHESTRATOR RESEARCH DESIGNER COORDINATOR ANALYZER REFLECTION; do
    AI_ROLE="$role" java -Dengine.home="$PWD" -jar "$jar" >"runtime/ai/${role,,}.log" 2>&1 &
    pids+=("$!")
done
# Confirms all six listeners belong to the six children, including after the aggregate health probe.
agents_own_listeners() {
    local i
    for ((i=0; i<6; i++)); do owns_listener "${pids[i]}" "${ports[i]}" || return 1; done
}
deadline=$((SECONDS + 60))
while (( SECONDS < deadline )); do
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || { echo 'An AI service exited. Inspect runtime/ai/*.log.' >&2; exit 1; }; done
    if agents_own_listeners && timeout 8s java -Dengine.home="$PWD" -jar "$jar" --check >/dev/null 2>&1 && agents_own_listeners; then
        echo 'Six AI agents are ready. Business > 08 AI-assisted simulation and offer feedback.'
        wait -n "${pids[@]}"
        exit 1
    fi
    sleep 1
done
echo 'AI readiness timed out. Inspect runtime/ai/*.log.' >&2
exit 1
