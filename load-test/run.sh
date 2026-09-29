#!/usr/bin/env bash
# Starts the stack with fixed resources, runs the k6 load test and checks that no invoice is lost.
# Options of invoices.js can be passed as environment variables, e.g. STEADY_RATE=10 ./load-test/run.sh
# Arguments are passed to "k6 run", e.g. ./load-test/run.sh --out csv=results/metrics.csv
set -euo pipefail

cd "$(dirname "$0")/.."

readonly compose=(docker compose -f docker-compose.yml -f load-test/docker-compose.yml)
readonly run_id=$(date +%Y%m%d%H%M%S)
readonly summary="results/summary-$run_id.json"
# Delivery runs asynchronously and is behind after the peak, see docs/load-test.md.
readonly delivery_timeout_seconds=${DELIVERY_TIMEOUT_SECONDS:-1200}

sql() {
    "${compose[@]}" exec -T postgres psql -U invoicehub -d invoicehub -tA -c "$1"
}

invoices_of_run() {
    echo "from invoice where filename like 'load-test-$run_id-%'"
}

wait_for_delivery() {
    local deadline=$((SECONDS + delivery_timeout_seconds))
    local open
    while true; do
        open=$(sql "select count(*) $(invoices_of_run) and status in ('RECEIVED', 'VALID')")
        if [[ "$open" -eq 0 ]]; then
            return 0
        fi
        if ((SECONDS >= deadline)); then
            echo "$open invoices still not delivered after $delivery_timeout_seconds seconds" >&2
            return 1
        fi
        echo "Waiting for delivery of $open invoices"
        sleep 10
    done
}

echo "Starting stack for run $run_id"
"${compose[@]}" up --build --detach --wait

mkdir -p load-test/results
k6_exit_code=0
"${compose[@]}" run --rm \
    -e RUN_ID="$run_id" \
    -e STEADY_RATE -e STEADY_DURATION -e PEAK_RATE \
    -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_EXPORT="results/report-$run_id.html" \
    k6 run --summary-export "$summary" "$@" invoices.js || k6_exit_code=$?

delivery_exit_code=0
wait_for_delivery || delivery_exit_code=$?

echo "Status of the invoices:"
sql "select status, count(*) $(invoices_of_run) group by status order by status"

sent=$(jq '.metrics.documents_sent.count' "load-test/$summary")
stored=$(sql "select count(*) $(invoices_of_run)")
echo "Documents sent: $sent, invoices in database: $stored"

if [[ "$sent" -ne "$stored" ]]; then
    echo "Invoices lost: $((sent - stored))" >&2
    exit 1
fi
if [[ "$k6_exit_code" -ne 0 || "$delivery_exit_code" -ne 0 ]]; then
    echo "Load test failed, see above" >&2
    exit 1
fi
echo "No invoice lost"
