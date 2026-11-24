#!/usr/bin/env bash
# Loads one bank's local configuration and reserves a fixed isolation boundary for Anna.
lending_bank="${LENDING_BANK:-emma}"
case "$lending_bank" in
    emma) bank_runtime="$PWD/runtime" ;;
    anna) bank_runtime="$PWD/runtime/anna" ;;
    *) echo 'Choose bank emma or anna.' >&2; exit 2 ;;
esac

if [[ "$lending_bank" == anna ]]; then
    [[ ! -L "$PWD/runtime" && ! -L "$bank_runtime" ]] || { echo 'Use a dedicated Anna runtime directory.' >&2; exit 1; }
    [[ -f "$bank_runtime/local.env" && ! -L "$bank_runtime/local.env" ]] || {
        echo 'Configure the Anna PostgreSQL database in runtime/anna/local.env. See README.md.' >&2; exit 1;
    }
    unset DATABASE_URL AI_DATABASE_URL AI_DB_USER AI_DB_PASSWORD AI_OUTCOME_SOURCE_ID
    unset AI_CREDENTIAL_FILE AI_FEEDBACK_CREDENTIAL_FILE AI_PERSONALIZATION_CREDENTIAL_FILE
    unset AI_MCP_CONFIG AI_FEEDBACK_MCP_CONFIG AI_PERSONALIZATION_MCP_CONFIG
fi
set -a
if [[ -f "$bank_runtime/local.env" ]]; then source <(sed 's/\r$//' "$bank_runtime/local.env"); fi
owner_database="${DATABASE_URL:-}"
owner_broker="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
if [[ "${1:-base}" == ai ]]; then
    if [[ -f "$bank_runtime/ai.env" ]]; then source <(sed 's/\r$//' "$bank_runtime/ai.env"); fi
fi
set +a
export DATABASE_URL="$owner_database" KAFKA_BOOTSTRAP_SERVERS="$owner_broker"
export LENDING_BANK="$lending_bank" ENGINE_RUNTIME="$bank_runtime"
export ENGINE_API_PORT=8090 ENGINE_CREDIT_PORT=8091 ENGINE_MARKETING_PORT=8092 ENGINE_TERMINAL_PORT=2323
if [[ "$lending_bank" == anna ]]; then
    if [[ ! "${DATABASE_URL:-}" =~ ^postgres(ql)?://[^/]+@(localhost|127\.0\.0\.1)(:[0-9]+)?/lending_anna_karenina$ ]]; then
        echo 'Anna requires the dedicated local PostgreSQL database lending_anna_karenina.' >&2; exit 1
    fi
    export ENGINE_API_PORT=8290 ENGINE_CREDIT_PORT=8291 ENGINE_MARKETING_PORT=8292 ENGINE_TERMINAL_PORT=2423
    export AI_BASE_PORT=8300 AI_FEEDBACK_BASE_PORT=8310 AI_PERSONALIZATION_BASE_PORT=8320
    export AI_DATABASE_URL="$DATABASE_URL" KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9192
    export AI_ENGINE_URL=http://127.0.0.1:8290/api/v1/ AI_MARKETING_URL=http://127.0.0.1:8292/api/v1/
    export AI_POLICY_DIR="$bank_runtime/policy-documents"
    export AI_MCP_CONFIG="$bank_runtime/mcp-servers.json"
    export AI_FEEDBACK_MCP_CONFIG="$AI_MCP_CONFIG" AI_PERSONALIZATION_MCP_CONFIG="$AI_MCP_CONFIG"
else
    # Keep old Emma configuration, while reserving Anna's database and event listener.
    for database in "${DATABASE_URL:-}" "${AI_DATABASE_URL:-}"; do
        if [[ "$database" =~ /lending_anna_karenina([?].*)?$ ]]; then
            echo 'The lending_anna_karenina database is reserved for Anna.' >&2; exit 1
        fi
    done
    if [[ "${KAFKA_BOOTSTRAP_SERVERS:-}" == *:9192* ]]; then
        echo 'Kafka port 9192 is reserved for Anna.' >&2; exit 1
    fi
fi
