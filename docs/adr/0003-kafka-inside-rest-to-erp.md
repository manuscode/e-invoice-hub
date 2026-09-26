# 3. Kafka inside, REST to the ERP

Date: 2026-09-26

## Status

Accepted

## Context

Valid invoices must reach the ERP. The ERP can be slow or not available.
An upload should not fail or wait because of the ERP.
Most real ERPs offer REST or SOAP, not Kafka.

## Decision

- The `invoice` module publishes `InvoiceAccepted` in the same transaction as the invoice (outbox with Spring Modulith).
- The event goes to Kafka.
- The `delivery` module reads from Kafka and calls the ERP via REST.
- Retry with exponential backoff, after that the message goes to a dead letter topic. `4xx` from the ERP goes there directly.

## Consequences

- Upload and ERP are decoupled. The ERP can be down without losing invoices.
- Retry and dead letter handling sit next to the unreliable system.
- Kafka is one more component to run.
