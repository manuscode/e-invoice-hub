# Load test

How much load can the hub handle, where is the limit and is any invoice lost on the way?

## Run it

```bash
./load-test/run.sh
```

The script

1. builds the hub and starts the whole stack with fixed resources (`docker-compose.yml` + `load-test/docker-compose.yml`),
2. runs the k6 script `load-test/invoices.js` in a container,
3. waits until every accepted invoice is delivered to the ERP simulator (at most 20 minutes),
4. compares the documents sent by k6 with the invoices in the database and fails if one is missing.

Results are written to `load-test/results/`: `summary-<run>.json` and an HTML report with time series (`report-<run>.html`).

Rates and duration can be changed with environment variables, arguments are passed to `k6 run`:

```bash
STEADY_RATE=8 STEADY_DURATION=10m PEAK_RATE=30 ./load-test/run.sh --out csv=results/metrics.csv
```

## Setup

| Container | CPU | Memory | Notes |
|---|---|---|---|
| invoice-hub | 2 | 1.5 GB | heap 60% of the container |
| postgres | 1 | 512 MB | |
| kafka | 1 | 1 GB | heap 512 MB |
| erp-simulator | 0.5 | 512 MB | 200 ms latency, 10% `503` as in the demo |
| keycloak, lgtm, greenmail, sftp, k6 | – | – | not limited |

Tracing is sampled at 100% and exported to Grafana, the same as in the demo.

Load per iteration, repeated every 20 iterations:

| Case | Share | Requests | Expected result |
|---|---|---|---|
| valid XRechnung (UBL and CII alternating) | 70% | 1 | `201`, `VALID` |
| invalid XRechnung (missing buyer reference) | 15% | 1 | `201`, `REJECTED` |
| duplicate: same invoice number, other content | 10% | 2 | `201 VALID`, then `201 DUPLICATE` of the first |
| same document again | 5% | 2 | `201 VALID`, then `200` with the same id |

Each document gets its own invoice number, so every document is new for the hub.
ZUGFeRD PDFs are not part of the mix, because k6 can't change the invoice number inside the PDF.

Scenarios:

- **Warm-up:** 2 iterations/s for 30 s, not part of the numbers. Without it JIT and the first KoSIT checks raise the p95 of the steady phase to almost 4 s.
- **Steady:** 5 iterations/s (≈ 5.8 requests/s) for 5 minutes. Thresholds: p95 < 500 ms, p99 < 1 s, no errors.
- **Peak:** end of month, rises from 5 to 20 iterations/s in 3 minutes, holds for 1 minute.

## Results

> [!WARNING]
> The numbers were measured on a MacBook Air M2 with 8 GB RAM, Docker VM with 4 CPUs and 7.7 GB.
> The host swapped heavily during all runs (IntelliJ open, 7.8 GB swap used), the Docker VM froze for several minutes
> more than once. Only phases without a freeze are used below. The numbers are a lower bound, not a benchmark.
> A clean run on a host with at least 16 GB RAM is still open.

| | Steady (5 iterations/s) | Peak |
|---|---|---|
| Throughput | 5.8 requests/s, all processed | limit at about 15 iterations/s (≈ 17 requests/s) |
| Latency p50 / p95 / p99 | 66 ms / 136 ms / 191 ms | rises to 16 s / 34 s / 43 s above the limit |
| Errors | 0 | 2 of 4839 requests after the k6 timeout of 60 s, both stored anyway |
| Hub CPU | about 0.5 of 2 CPUs | about 1.5 of 2 CPUs |
| Delivery to the ERP | keeps up | about 4.5 invoices/s, backlog grows |

Steady: 977 upload requests from two phases without a freeze.
Peak: probe run with a rise up to 40 iterations/s. Above about 15 iterations/s k6 needed more and more VUs (138 at
19 iterations/s, the maximum of 300 at 24), because the hub answered slower than new requests came in.

### No invoice lost

Probe run: 4953 documents sent, 4953 invoices in the database, including the two requests that ran into the client
timeout. Every invoice had the expected status (`VALID`, `REJECTED`, `DUPLICATE`), none stayed in `RECEIVED`.

`run.sh` checks this after every run and fails if the numbers differ.

## Bottlenecks

### 1. Intake: KoSIT validation is serialized by a Saxon lock

A thread dump at the limit showed 119 of 200 Tomcat threads `BLOCKED` in `net.sf.saxon.om.NamePool.allocateFingerprint`.
All checks share one Saxon `Processor` and with it one `NamePool`, which is synchronized. The hub uses only about
1.5 of its 2 CPUs at the limit, so more CPU alone doesn't help.

Above the limit Tomcat accepts up to 200 parallel requests, they all wait for the lock and the latency grows for all
of them. There is no back pressure, the client only sees long response times.

### 2. Delivery: one consumer for a slow ERP

The ERP simulator needs 200 ms per invoice. `ErpDeliveryListener` runs with one consumer and one partition, so the hub
delivers at most 5 invoices/s, with the 10% failures about 4.5/s. At 5 iterations/s about 4.25 valid invoices/s come in,
so already the steady load is close to that limit. After the peak the backlog needs several minutes to drain.
No invoice is lost, the events stay in Kafka, but the ERP gets the invoices late.

## Next steps

1. **More validation in parallel:** a small pool of `Check` instances, each with its own Saxon `Processor`, instead of
   one shared instance. Measure again whether the hub then uses both CPUs.
2. **Limit parallel validations:** a bulkhead (e.g. a semaphore with the pool size) and `429` with `Retry-After` when it
   is full. Clients get a fast answer instead of waiting 30 s, the mail and SFTP intake simply try again later.
3. **Faster delivery:** more partitions for `invoice-accepted` and listener concurrency matching the ERP, e.g. 4.
   How much load the real ERP can take must be clarified with its operators.
4. **Alert on backlog:** consumer lag of `invoice-hub-delivery` and number of invoices in `VALID` older than 15 minutes
   as metric and alert in Grafana.
5. **Clean run:** repeat the test on a host with enough memory and replace the numbers above.
