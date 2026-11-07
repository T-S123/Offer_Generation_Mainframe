#!/usr/bin/env bash
# Starts distinct simulation, investigation and customer-personalization groups behind the existing API facade.
# The presenter supervisor pauses normal/API-only launches during local resets; cleanup touches only owned processes.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ -f runtime/local.env ]]; then set -a; source <(sed 's/\r$//' runtime/local.env); set +a; fi
if [[ -f runtime/ai.env ]]; then set -a; source <(sed 's/\r$//' runtime/ai.env); set +a; fi
jar=ai-service/target/ai-simulation-1.0.0.jar
[[ -f "$jar" ]] || { echo 'Build AI first: scripts/build-ai.ps1' >&2; exit 1; }
if [[ "${1:-}" == "--init" ]]; then exec java -Dengine.home="$PWD" -jar "$jar" --init; fi
if [[ "${PRESENTER_GROUP:-}" != ai && ( $# -eq 0 || "${1:-}" == "--api-only" ) ]]; then exec python3 scripts/presenter-group.py ai bash scripts/run-ai.sh "$@"; fi
if [[ "${1:-}" == "--api-only" ]]; then export AI_API_ONLY=true; shift; fi
if [[ "${1:-}" == "--check" ]]; then exec java -Dengine.home="$PWD" -jar "$jar" --check; fi
[[ $# -eq 0 ]] || { echo 'Use --init, --check, --api-only, or no arguments.' >&2; exit 1; }
[[ -n "${AI_DATABASE_URL:-${DATABASE_URL:-}}" ]] || { echo 'Configure AI_DATABASE_URL or DATABASE_URL in runtime/ai.env.' >&2; exit 1; }
[[ "${AI_API_ONLY:-false}" == true || -n "${OPENAI_API_KEY:-}" ]] || { echo 'Configure OPENAI_API_KEY in the local environment or runtime/ai.env.' >&2; exit 1; }
[[ -s runtime/api-token && -s runtime/marketing-api-token ]] || { echo 'Start the existing engine services first.' >&2; exit 1; }
source scripts/startup-guard.sh
base_port="${AI_BASE_PORT:-8100}"
[[ "$base_port" =~ ^[0-9]{1,5}$ ]] && (( 10#$base_port >= 1 && 10#$base_port <= 65507 )) || { echo 'AI_BASE_PORT must be from 1 to 65507.' >&2; exit 1; }
base_port=$((10#$base_port))
feedback_port="${AI_FEEDBACK_BASE_PORT:-$((base_port + 10))}"
[[ "$feedback_port" =~ ^[0-9]{1,5}$ ]] && (( 10#$feedback_port >= 1 && 10#$feedback_port <= 65529 )) || { echo 'AI_FEEDBACK_BASE_PORT must be from 1 to 65529.' >&2; exit 1; }
feedback_port=$((10#$feedback_port))
personalization_port="${AI_PERSONALIZATION_BASE_PORT:-$((base_port + 20))}"
[[ "$personalization_port" =~ ^[0-9]{1,5}$ ]] && (( 10#$personalization_port >= 1 && 10#$personalization_port <= 65527 )) || { echo 'AI_PERSONALIZATION_BASE_PORT must be from 1 to 65527.' >&2; exit 1; }
personalization_port=$((10#$personalization_port))
ports=(); roles=(); networks=()
all_roles=(ORCHESTRATOR RESEARCH DESIGNER COORDINATOR ANALYZER REFLECTION ELIGIBILITY CONTEXT GUIDE)
for ((i=0; i<6; i++)); do ports+=("$((base_port+i))"); roles+=("${all_roles[i]}"); networks+=(SIMULATION); done
for i in 0 1 4 5 6; do ports+=("$((feedback_port+i))"); roles+=("${all_roles[i]}"); networks+=(FEEDBACK); done
for i in 0 7 2 6 8; do ports+=("$((personalization_port+i))"); roles+=("${all_roles[i]}"); networks+=(PERSONALIZATION); done
for ((i=0; i<16; i++)); do for ((j=i+1; j<16; j++)); do [[ "${ports[i]}" != "${ports[j]}" ]] || { echo 'A2A network ports overlap.' >&2; exit 1; }; done; done
export AI_BASE_PORT="$base_port" AI_FEEDBACK_BASE_PORT="$feedback_port" AI_PERSONALIZATION_BASE_PORT="$personalization_port"
if [[ "${AI_API_ONLY:-false}" == true ]]; then
    guard_startup 'Performance API' runtime/ai-launcher.lock "$base_port"
    AI_NETWORK=SIMULATION java -Dengine.home="$PWD" -jar "$jar" --init
    exec env AI_NETWORK=SIMULATION AI_ROLE=ORCHESTRATOR java -Dengine.home="$PWD" -jar "$jar"
fi
guard_startup 'AI networks' runtime/ai-launcher.lock "${ports[@]}"
AI_NETWORK=SIMULATION java -Dengine.home="$PWD" -jar "$jar" --init
pids=()
cleanup() { trap - EXIT INT TERM; for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for ((i=0; i<16; i++)); do
    role="${roles[i]}"; network="${networks[i]}"
    log="runtime/ai/${role,,}.log"; [[ "$network" == SIMULATION ]] || log="runtime/ai/${network,,}/${role,,}.log"
    AI_NETWORK="$network" AI_ROLE="$role" java -Dengine.home="$PWD" -jar "$jar" >"$log" 2>&1 &
    pids+=("$!")
done
agents_own_listeners() { local i; for ((i=0; i<16; i++)); do owns_listener "${pids[i]}" "${ports[i]}" || return 1; done; }
deadline=$((SECONDS+90))
while (( SECONDS < deadline )); do
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || { echo 'An agent exited. Inspect runtime/ai, runtime/ai/feedback and runtime/ai/personalization logs.' >&2; exit 1; }; done
    if agents_own_listeners && timeout 12s env AI_NETWORK=SIMULATION java -Dengine.home="$PWD" -jar "$jar" --check >/dev/null 2>&1 && agents_own_listeners; then
        echo 'Simulation (six), investigation (five), and customer personalization (five) are ready.'
        wait -n "${pids[@]}"; exit 1
    fi
    sleep 1
done
echo 'AI readiness timed out. Inspect runtime/ai, runtime/ai/feedback and runtime/ai/personalization logs.' >&2
exit 1
