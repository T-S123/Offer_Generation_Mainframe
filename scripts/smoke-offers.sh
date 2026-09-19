#!/usr/bin/env bash
# Starts the normal application launcher and checks service health, copy processing and terminal modes. It
# stops only the launcher and child processes it created.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
for port in 8090 8091 8092; do
    if ss -ltnH "sport = :$port" | grep -q .; then
        echo "Port $port is already in use; stop the existing application before this smoke test." >&2
        exit 1
    fi
done
bash scripts/run.sh >runtime/offer-launch-smoke.log 2>&1 &
launcher=$!
# Stops and waits for the application launcher created by this smoke test.
cleanup() { kill -TERM "$launcher" 2>/dev/null || true; wait "$launcher" 2>/dev/null || true; }
trap cleanup EXIT
for attempt in {1..60}; do
    if ! kill -0 "$launcher" 2>/dev/null; then echo 'Launcher exited; inspect runtime/offer-launch-smoke.log.' >&2; exit 1; fi
    if [[ -f runtime/marketing-api-token ]] && curl --fail --silent --max-time 2 -H "Authorization: Bearer $(cat runtime/marketing-api-token)" http://127.0.0.1:8092/api/v1/health >runtime/offer-health-smoke.json; then
        if grep -q '"backfill":"RUNNING"' runtime/offer-health-smoke.json && grep -q '"kafka":"RUNNING"' runtime/offer-health-smoke.json; then
            curl --fail --silent --max-time 3 -H "Authorization: Bearer $(cat runtime/marketing-api-token)" http://127.0.0.1:8092/api/v1/storage/health >runtime/storage-health-smoke.json || continue
            curl --fail --silent --max-time 3 -H "Authorization: Bearer $(cat runtime/api-token)" http://127.0.0.1:8090/api/v1/offer-copies/health >runtime/copy-health-smoke.json || continue
            grep -q '"prescreenScanner":"RUNNING"' runtime/storage-health-smoke.json || continue
            grep -q '"publisher":"RUNNING"' runtime/storage-health-smoke.json || continue
            [[ $(grep -o '"kafka":"RUNNING"' runtime/copy-health-smoke.json | wc -l) -eq 2 ]] || continue
            [[ $(grep -o '"httpRecovery":"RUNNING"' runtime/copy-health-smoke.json | wc -l) -eq 2 ]] || continue
            curl --fail --silent --max-time 3 -H "Authorization: Bearer $(cat runtime/api-token)" http://127.0.0.1:8090/api/v1/campaign-execution/health >runtime/execution-health-smoke.json || continue
            grep -q '"worker":"RUNNING"' runtime/execution-health-smoke.json || continue
            grep -q '"pipeline":"RUNNING"' runtime/execution-health-smoke.json || continue
            grep -q '"repositoryRecovery":"RUNNING"' runtime/execution-health-smoke.json || continue
            s3270 -model 3279-2 127.0.0.1:2323 >runtime/presentation-launch-smoke.txt <<'TERMINAL'
Wait(5,InputField)
Ascii()
String("02")
Enter()
Wait(5,InputField)
Ascii()
PF(3)
Wait(5,InputField)
String("01")
Enter()
Wait(5,InputField)
Ascii()
Quit()
TERMINAL
            grep -q 'Select a Mode' runtime/presentation-launch-smoke.txt
            grep -q 'Business Simulation Engine' runtime/presentation-launch-smoke.txt
            grep -q 'Customer Menu' runtime/presentation-launch-smoke.txt
            cat runtime/offer-health-smoke.json runtime/storage-health-smoke.json runtime/copy-health-smoke.json runtime/execution-health-smoke.json
            echo
            echo 'Three-process launcher, storage/copy consumers, automatic Steps 1-7 and Step 8 Customer/Business terminals verified.'
            exit 0
        fi
    fi
    sleep 1
done
echo 'Marketing startup timed out; inspect runtime/marketing-service.log.' >&2
exit 1
