#!/usr/bin/env bash
# Prevents competing launchers from touching live databases/logs and verifies listener ownership.
# Sourced after the caller enters the project directory; descriptor 9 retains the launcher lock.

# Takes a per-project lock and rejects occupied service ports before any Java process or log is opened.
guard_startup() {
    local label="$1" lock_file="$2" port listeners
    shift 2
    command -v flock >/dev/null && command -v ss >/dev/null || {
        echo 'Startup requires flock and ss in WSL (util-linux and iproute2).' >&2
        return 1
    }
    mkdir -p runtime
    exec 9>"$lock_file"
    if ! flock -n 9; then
        echo "$label is already running for this project. Stop its existing launcher with Ctrl+C before starting another." >&2
        return 1
    fi
    for port in "$@"; do
        listeners=$(ss -H -ltn "sport = :$port") || return 1
        if [[ -n "$listeners" ]]; then
            echo "$label cannot start: port $port is already in use. Stop the existing service in its original tab, then retry. No services were started." >&2
            return 1
        fi
    done
}

# Accepts readiness only when the expected child owns the listening socket, not an older healthy server.
owns_listener() {
    local pid="$1" port="$2" listeners
    kill -0 "$pid" 2>/dev/null || return 1
    listeners=$(ss -H -ltnp "sport = :$port") || return 1
    [[ "$listeners" == *"pid=$pid,"* ]]
}
