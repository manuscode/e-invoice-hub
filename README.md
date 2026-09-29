# E-Invoice Hub

[![Build](https://github.com/manuscode/e-invoice-hub/actions/workflows/build.yml/badge.svg)](https://github.com/manuscode/e-invoice-hub/actions/workflows/build.yml)

A service that receives e-invoices by REST, mail and SFTP, checks them with the official KoSIT rules and delivers
them to the ERP. Small scope, but built like a production system.

**Tech stack:** Java 25 · Spring Boot 4 · Spring Modulith · Kafka · Postgres · Keycloak · KoSIT Validator ·
Mustang · OpenTelemetry · Grafana · Testcontainers · k6

## Problem

Since 1 January 2025 all companies in Germany must be able to receive e-invoices (XRechnung, ZUGFeRD).
In many companies it still looks like this:

- Invoices come by mail, as PDF, XML or both.
- Someone checks them by hand and types them into the ERP.
- Duplicates and wrong invoices are found late, often only at payment.

## Solution

The hub is the single entry point for incoming invoices:

- **Receive** over three channels: REST upload, IMAP mailbox and SFTP directory.
- **Detect the format:** XRechnung UBL, XRechnung CII and ZUGFeRD / Factur-X PDF. Everything else is rejected.
- **Validate** with the [KoSIT Validator](https://github.com/itplr-kosit/validator) and the official XRechnung
  configuration, the same rules the public authorities use. Each invoice gets a validation report.
- **Find duplicates:** the same document sent again returns the existing invoice. The same invoice in another document
  (e.g. PDF by mail and XML by SFTP) gets status `DUPLICATE` and a link to the original.
- **Deliver** valid invoices to the ERP by REST, with retry and dead letter topic. If the ERP is down, no invoice is
  lost and the intake doesn't slow down.
- **Operate:** metrics, logs and traces in Grafana. You can follow one invoice from the upload to the ERP.

Invoice status:

```mermaid
stateDiagram-v2
    [*] --> RECEIVED
    RECEIVED --> REJECTED: KoSIT check failed, unknown format or profile
    RECEIVED --> DUPLICATE: same seller VAT ID + invoice number
    RECEIVED --> VALID
    VALID --> DELIVERED: ERP accepted
    VALID --> DELIVERY_FAILED: retries exhausted, message in DLT
```

## Architecture

Arrows show how invoices and data flow.

```mermaid
flowchart TB
    supplier([Supplier / portal])
    mailbox[(IMAP mailbox)]
    sftp[(SFTP server)]
    keycloak[Keycloak<br/>access tokens]

    subgraph hub [invoice-hub]
        direction LR
        intake[intake<br/>receives from all channels]
        validation[validation<br/>detects format, KoSIT check]
        invoice[invoice<br/>maps, detects duplicates]
        delivery[delivery<br/>sends to ERP, retries]
        intake -- raw document --> validation
        validation -- checked invoice --> invoice
        delivery -- status DELIVERED /<br/>DELIVERY_FAILED --> invoice
    end

    postgres[(Postgres<br/>invoices, raw documents,<br/>event publication as outbox)]
    kafka[[Kafka<br/>invoice-accepted,<br/>retry and DLT topics]]
    erp[ERP<br/>simulated]
    lgtm[Grafana<br/>metrics, logs, traces]

    supplier -- upload via REST --> intake
    supplier -- mail --> mailbox
    supplier -- file --> sftp
    mailbox -- polled attachments --> intake
    sftp -- polled files --> intake
    supplier -- client credentials --> keycloak
    keycloak -- signing keys<br/>to check tokens --> intake
    invoice -- invoice, status and<br/>InvoiceAccepted in one transaction --> postgres
    invoice -- InvoiceAccepted<br/>after commit --> kafka
    kafka -- InvoiceAccepted --> delivery
    delivery -- failed attempts --> kafka
    delivery -- invoice data via REST --> erp
    hub -- metrics, logs, traces --> lgtm
    erp -- metrics, logs, traces --> lgtm
    %% Invisible link, keeps Postgres above Kafka and the diagram narrow.
    postgres ~~~ kafka

    %% Transparent fills and the text color of the theme, so the diagram fits light and dark themes.
    classDef channel fill:#2563eb26,stroke:#2563eb
    classDef module fill:#16a34a33,stroke:#16a34a,stroke-width:2px
    classDef storage fill:#d9770633,stroke:#d97706
    classDef target fill:#7c3aed33,stroke:#7c3aed,stroke-width:2px
    classDef platform fill:#64748b26,stroke:#64748b
    class supplier,mailbox,sftp channel
    class intake,validation,invoice,delivery module
    class postgres,kafka storage
    class erp target
    class keycloak,lgtm platform
    style hub fill:#16a34a0d,stroke:#16a34a,stroke-width:2px
```

Blue: input channels · Green: the hub and its modules · Amber: storage and messaging · Purple: target system ·
Gray: security and operations

- **One Spring Boot service** (Java 25, Spring Boot 4) with four modules. The code dependencies go
  `intake` → `validation` → `invoice` ← `delivery`, `invoice` doesn't know who consumes its events.
  Spring Modulith checks the module borders on every build.
- **Validation is synchronous**, the caller gets the result in the response. **Delivery is asynchronous.**
- The invoice and the `InvoiceAccepted` event are saved in one transaction (outbox with Spring Modulith), then the
  event goes to Kafka. No event gets lost, and there is no event for an invoice that wasn't saved.

## Decisions

| Decision | Why | Trade-off |
|---|---|---|
| [Modular monolith](docs/adr/0002-modular-monolith.md) | Small scope, one team. Microservices would only add operations effort. | Modules can't scale on their own, but can be split along the checked borders. |
| [Kafka inside, REST to the ERP](docs/adr/0003-kafka-inside-rest-to-erp.md) | Most real ERPs offer REST, not Kafka. Retry and DLT belong next to the unreliable system. | One more component to run. |
| Outbox with Spring Modulith | Invoice and event in one transaction, no own outbox table or relay. | Less own code to show. |
| [Raw documents in Postgres](docs/adr/0004-raw-documents-in-postgres.md) | One store, one transaction, simple backup. Invoices are small. | Not for big volumes, S3 would be the next step. |
| [KoSIT with official XRechnung configuration](docs/adr/0005-xrechnung-configuration-at-build-time.md) | Same rules as the authorities, no own rule set. Downloaded at build time with checksum. | Update with every XRechnung release. |
| [Duplicate detection in two levels](docs/adr/0006-duplicate-detection.md) | Retries of a channel must be safe, real duplicates must be visible. Both enforced by unique constraints. | The fallback on the seller name misses names written differently. |
| [OAuth2 with Keycloak](docs/adr/0007-oauth2-resource-server-with-keycloak.md) | Clients are systems, client credentials flow. Roles for upload and read. | Keycloak is one more component. |
| Local only | The whole system starts with one command, with the same containers as the tests (Testcontainers). | No public URL. |

ZUGFeRD profiles MINIMUM and BASIC WL are rejected on purpose. They don't count as e-invoice in Germany.

## Results

- **All formats and channels** work end to end: REST, mail and SFTP, UBL, CII and ZUGFeRD. See the
  [demo walkthrough](#demo-walkthrough).
- **No invoice lost:** the load test sent 4953 documents and 4953 invoices ended up in the database, each with the
  expected status. `load-test/run.sh` checks this on every run.
- **Load:** steady 5.8 requests/s with p95 136 ms and p99 191 ms, the limit is about 17 requests/s. Measured on a
  laptop that was swapping, so this is a lower bound. Details, bottlenecks and next steps in [docs/load-test.md](docs/load-test.md).
- **Bottlenecks found:** a lock in Saxon makes KoSIT validations run one after the other, and one delivery consumer
  can't keep up with a slow ERP after a peak. Both are documented with a fix.
- **Security:** only JWTs from Keycloak with the right role, no invoice content or IBAN in the logs, document size
  limited on all channels, hardened XML parsing, validation report served with a strict Content Security Policy,
  containers without root.
- **Tests:** unit and integration tests against real Postgres, Kafka, Keycloak, GreenMail and SFTP (Testcontainers),
  plus the module structure test. They run in CI on every push to `main` and on every pull request.

## Run it locally

Requirements: Docker with Docker Compose and about 6 GB memory for the Docker VM, `curl` and `jq` for the demo script.
Java is not needed, everything is built inside Docker.

```bash
docker compose up --build --detach --wait
./demo/load-data.sh
```

The first start takes a few minutes because Maven downloads the dependencies and the XRechnung configuration.

| What | URL | Login |
|---|---|---|
| Invoice Hub API | http://localhost:8080/api/invoices | token from Keycloak, see below |
| Grafana dashboard | http://localhost:3000/d/invoice-hub | none |
| ERP simulator | http://localhost:8081/erp/invoices | none |
| Keycloak | http://localhost:8180 | `admin` / `admin` |

All passwords, client secrets and keys in this repository are for the local demo only. That's why all ports are
only open on `localhost`.

Get a token and upload an invoice:

```bash
TOKEN=$(curl -s http://localhost:8180/realms/invoice-hub/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=demo-uploader -d client_secret=demo-uploader-secret | jq -r .access_token)

curl -s http://localhost:8080/api/invoices -H "Authorization: Bearer $TOKEN" \
  -F "file=@samples/rest/01-xrechnung-ubl-valid.xml;type=application/xml" | jq
```

For `GET /api/invoices/{id}` and `GET /api/invoices/{id}/report` use the client `demo-reader` with the secret
`demo-reader-secret`. The uploader may not read and the reader may not upload.

Stop the stack with `docker compose down`, with `-v` the invoices are deleted too. The ERP simulator keeps its
invoices only in memory.

## Demo walkthrough

`./demo/load-data.sh` sends all files from `samples/`. You can run it more than once, the hub recognizes documents it
has seen before. The script prints the result of each REST upload:

| Sample | Channel | Result |
|---|---|---|
| `rest/01-xrechnung-ubl-valid.xml` | REST | `201`, `VALID`, then `DELIVERED` |
| `rest/02-xrechnung-cii-valid.xml` | REST | `201`, `VALID`, then `DELIVERED` |
| `rest/03-zugferd-en16931-valid.pdf` | REST | `201`, `VALID`, then `DELIVERED` |
| `rest/04-xrechnung-ubl-duplicate-of-03.xml` | REST | `201`, `DUPLICATE` of 03: same invoice as XML instead of PDF |
| `rest/05-xrechnung-ubl-missing-buyer-reference.xml` | REST | `201`, `REJECTED`, `VALIDATION_FAILED` (rule `BR-DE-15`) |
| `rest/06-xrechnung-cii-missing-buyer-reference.xml` | REST | `201`, `REJECTED`, `VALIDATION_FAILED` (rule `BR-DE-15`) |
| `rest/07-zugferd-minimum-profile.pdf` | REST | `201`, `REJECTED`, `UNSUPPORTED_PROFILE` |
| `rest/08-pdf-without-invoice.pdf` | REST | `201`, `REJECTED`, `UNSUPPORTED_FORMAT` |
| `rest/01-xrechnung-ubl-valid.xml` again | REST | `200` with the existing invoice |
| `mail/09-xrechnung-cii-valid.xml` | mail | `VALID`, then `DELIVERED`, after up to 30 seconds |
| `sftp/10-xrechnung-ubl-valid.xml` | SFTP | `VALID`, then `DELIVERED`, after up to 30 seconds |

The result of 04 depends on the order: it is only a duplicate if 03 came first.

What to look at:

1. **Validation report:** open the report of sample 05 with a reader token. It shows the failed rule `BR-DE-15`: the
   buyer reference (Leitweg-ID) is missing.

   ```bash
   READER_TOKEN=$(curl -s http://localhost:8180/realms/invoice-hub/protocol/openid-connect/token \
     -d grant_type=client_credentials -d client_id=demo-reader -d client_secret=demo-reader-secret | jq -r .access_token)
   curl -s http://localhost:8080/api/invoices/<id of 05>/report -H "Authorization: Bearer $READER_TOKEN" > /tmp/report.html
   open /tmp/report.html
   ```

   `open` is macOS, on Linux use `xdg-open`.

2. **ERP simulator:** http://localhost:8081/erp/invoices lists the five delivered invoices (01, 02, 03, 09, 10) as JSON
   in the internal model. Duplicates and rejected invoices never reach the ERP.
3. **Grafana dashboard "Invoice Hub":** received 10, by channel REST 8, mail 1, SFTP 1, by status 5 valid,
   4 rejected, 1 duplicate, 5 delivered. The simulator answers 10% of the calls with `503`. You see them in
   "ERP responses per minute", the hub retries them. Metrics are exported every 10 seconds.
4. **One invoice from upload to ERP:** enter the id of sample 01 in the field "Invoice id" at the top of the
   dashboard. In the panel "Traces" click on the trace `http post /api/invoices`: Tempo shows upload,
   validation, Kafka and the ERP call as one trace. The panel "Logs" shows the hub logs for this invoice.
5. **ERP down:** stop the simulator with `docker compose stop erp-simulator` and upload a new invoice, e.g.
   sample 01 with another invoice number (`TOKEN` from [Run it locally](#run-it-locally)):

   ```bash
   sed 's/2026-0001/2026-0011/' samples/rest/01-xrechnung-ubl-valid.xml > /tmp/2026-0011.xml
   curl -s http://localhost:8080/api/invoices -H "Authorization: Bearer $TOKEN" \
     -F "file=@/tmp/2026-0011.xml;type=application/xml" | jq
   ```

   The upload still returns `201 VALID` right away. The hub retries after 10, 30 and 90 seconds, then the invoice goes to
   the dead letter topic with status `DELIVERY_FAILED` and shows up in "In DLT".
   Start the simulator again with `docker compose start erp-simulator`.

## Development

Requirements: JDK 25 and Docker for Testcontainers.

```bash
./mvnw verify
```

To run the hub from the IDE, start the stack without the hub container and use the profile `local`:

```bash
docker compose up --detach --wait --scale invoice-hub=0
./mvnw -pl invoice-hub spring-boot:run -Dspring-boot.run.profiles=local
```

Load test with k6: `./load-test/run.sh`, see [docs/load-test.md](docs/load-test.md).

Project structure:

```text
invoice-hub/     the service, modules intake, validation, invoice, delivery
erp-simulator/   small ERP with configurable latency and failure rate
samples/         demo invoices by channel
demo/            script to load the samples into the running stack
load-test/       k6 script and runner
docker/          configuration of Keycloak, Grafana and SFTP for the local stack
docs/adr/        architecture decision records
```

## Before production

Known gaps, not fixed for the local demo:

- **No time limit for KoSIT validation.** A very large document blocks a request thread until the check is done.
  Fix: a timeout or a separate thread pool for the validation.
- **No authentication and no TLS for the ERP call.** OK for the simulator. A real ERP needs HTTPS and e.g. client
  credentials. The URL is set with `ERP_BASE_URL`.
- **No TLS for IMAP locally.** Set `invoice-hub.mail.protocol: imaps` to switch it on.
- **No memory limit in PDFBox for embedded files.** A PDF bomb can cause an `OutOfMemoryError`. The hub then exits and
  Docker restarts it. Nothing is lost, but the document stays `RECEIVED`. Fix: validation in a separate process with
  its own memory limit.

## Not in scope

Sending invoices, approval workflows, a real ERP, OCR for paper or image invoices and a cloud deployment.
See [ROADMAP.md](ROADMAP.md).
