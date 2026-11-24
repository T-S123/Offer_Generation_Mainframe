#!/usr/bin/env bash
# Starts one bank's networks and verifies independently supervised role listeners.
# Performance-only mode starts the two REST orchestrators with model workers disabled.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
source scripts/bank-env.sh ai
jar=ai-service/target/ai-simulation-1.0.0.jar
[[ -f "$jar" ]] || { echo 'Build AI first: scripts/build-ai.ps1' >&2; exit 1; }
launch_args=("$@")
check=false
api_only="${AI_API_ONLY:-false}"
if [[ "${1:-}" == "--api-only" ]]; then api_only=true; shift; fi
if [[ "${1:-}" == "--check" ]]; then check=true; shift; fi
selected=
if [[ "${1:-}" == "--network" && $# -eq 2 ]]; then
    selected="${2^^}"
    case "$selected" in SIMULATION|FEEDBACK|PERSONALIZATION) ;; *) echo 'Unknown network.' >&2; exit 1;; esac
    shift 2
fi
if [[ "${1:-}" == "--init" && $# -eq 1 ]]; then
    mkdir -p "$ENGINE_RUNTIME"
    exec flock "$ENGINE_RUNTIME/ai-init.lock" java -Dengine.home="$PWD" -jar "$jar" --init
fi
[[ $# -eq 0 ]] || { echo 'Use --init, --check, --api-only, or --network SIMULATION|FEEDBACK|PERSONALIZATION.' >&2; exit 1; }
check_args=(--check)
[[ -z "$selected" ]] || check_args+=("$selected")
if [[ "$check" == true ]]; then exec java -Dengine.home="$PWD" -jar "$jar" "${check_args[@]}"; fi
[[ "$api_only" != true || -z "$selected" ]] || { echo 'Use --api-only without --network.' >&2; exit 1; }
export AI_API_ONLY="$api_only"
group=ai
[[ -z "$selected" ]] || group="ai-${selected,,}"
if [[ "${PRESENTER_GROUP:-}" != "$group" ]]; then
    exec python3 scripts/presenter-group.py "$group" bash scripts/run-ai.sh "${launch_args[@]}"
fi
[[ -n "${AI_DATABASE_URL:-${DATABASE_URL:-}}" ]] || { echo 'Configure AI_DATABASE_URL or DATABASE_URL in runtime/ai.env.' >&2; exit 1; }
[[ "${AI_API_ONLY:-false}" == true || -n "${OPENAI_API_KEY:-}" ]] || { echo 'Configure OPENAI_API_KEY in the local environment or runtime/ai.env.' >&2; exit 1; }
[[ -s "$ENGINE_RUNTIME/api-token" && -s "$ENGINE_RUNTIME/marketing-api-token" ]] || { echo 'Start this bank’s engine services first.' >&2; exit 1; }
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
indices=()
listeners=()
for ((i=0; i<16; i++)); do
    [[ -z "$selected" || "${networks[i]}" == "$selected" ]] || continue
    [[ "$api_only" != true || ( "${roles[i]}" == ORCHESTRATOR && "${networks[i]}" != PERSONALIZATION ) ]] || continue
    indices+=("$i")
    listeners+=("${ports[i]}")
done
# A combined launch owns all networks. Individual launchers may coexist with one another.
mkdir -p "$ENGINE_RUNTIME"
exec 8>"$ENGINE_RUNTIME/ai-launch-mode.lock"
if [[ -z "$selected" ]]; then
    flock -n -x 8 || { echo 'An AI launcher is already running for this project.' >&2; exit 1; }
else
    flock -n -s 8 || { echo 'The combined AI launcher is already running for this project.' >&2; exit 1; }
fi
guard_startup 'AI networks' "$ENGINE_RUNTIME/$group-launcher.lock" "${listeners[@]}"
flock "$ENGINE_RUNTIME/ai-init.lock" java -Dengine.home="$PWD" -jar "$jar" --init
pids=()
cleanup() { trap - EXIT INT TERM; for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for i in "${indices[@]}"; do
    role="${roles[i]}"; network="${networks[i]}"
    log="$ENGINE_RUNTIME/ai/${role,,}.log"; [[ "$network" == SIMULATION ]] || log="$ENGINE_RUNTIME/ai/${network,,}/${role,,}.log"
    AI_NETWORK="$network" AI_ROLE="$role" java -Dengine.home="$PWD" -jar "$jar" >"$log" 2>&1 &
    pids+=("$!")
done
agents_own_listeners() { local i; for i in "${!pids[@]}"; do owns_listener "${pids[i]}" "${listeners[i]}" || return 1; done; }
if [[ "$api_only" == true ]]; then wait -n "${pids[@]}"; exit 1; fi
deadline=$((SECONDS+90))
while (( SECONDS < deadline )); do
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || { echo 'An agent exited. Inspect runtime/ai, runtime/ai/feedback and runtime/ai/personalization logs.' >&2; exit 1; }; done
    if agents_own_listeners && timeout 12s env AI_NETWORK=SIMULATION java -Dengine.home="$PWD" -jar "$jar" "${check_args[@]}" >/dev/null 2>&1 && agents_own_listeners; then
        if [[ -n "$selected" ]]; then echo "$selected agents are ready."; else
            echo 'Simulation (six), investigation (five), and customer personalization (five) are ready.'
        fi
        wait -n "${pids[@]}"; exit 1
    fi
    sleep 1
done
echo 'AI readiness timed out. Inspect runtime/ai, runtime/ai/feedback and runtime/ai/personalization logs.' >&2
exit 1
