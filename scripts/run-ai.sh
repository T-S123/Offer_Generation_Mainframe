#!/usr/bin/env bash
# Starts six independently hosted Java agents with owned process cleanup and bounded readiness checks.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ -f runtime/local.env ]]; then set -a; source runtime/local.env; set +a; fi
if [[ -f runtime/ai.env ]]; then set -a; source runtime/ai.env; set +a; fi
jar=ai-service/target/ai-simulation-1.0.0.jar
[[ -f "$jar" ]] || { echo 'Build AI first: scripts/build-ai.ps1' >&2; exit 1; }
if [[ "${1:-}" == "--init" ]]; then exec java -Dengine.home="$PWD" -jar "$jar" --init; fi
if [[ "${1:-}" == "--check" ]]; then exec java -Dengine.home="$PWD" -jar "$jar" --check; fi
[[ $# -eq 0 ]] || { echo 'Use --init, --check, or no arguments.' >&2; exit 1; }
[[ -n "${AI_DATABASE_URL:-${DATABASE_URL:-}}" ]] || { echo 'Configure AI_DATABASE_URL or DATABASE_URL in runtime/ai.env.' >&2; exit 1; }
[[ -n "${OPENAI_API_KEY:-}" ]] || { echo 'Configure OPENAI_API_KEY in the local environment or runtime/ai.env.' >&2; exit 1; }
[[ -s runtime/api-token && -s runtime/marketing-api-token ]] || { echo 'Start the existing engine services first.' >&2; exit 1; }
java -Dengine.home="$PWD" -jar "$jar" --init
pids=()
cleanup() { trap - EXIT INT TERM; for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for role in RESEARCH DESIGNER COORDINATOR ANALYZER REFLECTION ORCHESTRATOR; do
    AI_ROLE="$role" java -Dengine.home="$PWD" -jar "$jar" >"runtime/ai/${role,,}.log" 2>&1 &
    pids+=("$!")
done
deadline=$((SECONDS + 60))
while (( SECONDS < deadline )); do
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || { echo 'An AI service exited. Inspect runtime/ai/*.log.' >&2; exit 1; }; done
    if timeout 8s java -Dengine.home="$PWD" -jar "$jar" --check >/dev/null 2>&1; then
        echo 'Six AI agents are ready. Business > 08 AI-assisted simulation and offer feedback.'
        wait -n "${pids[@]}"
        exit 1
    fi
    sleep 1
done
echo 'AI readiness timed out. Inspect runtime/ai/*.log.' >&2
exit 1
