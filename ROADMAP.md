# Roadmap – E-Invoice Hub

## Background

Since 01.01.2025 all companies in Germany must be able to receive and process e-invoices.
From 2027 / 2028 they also have to send them.

But in many companies it still looks like this:

- Invoices come by mail, as PDF, XML or both
- Someone checks them by hand and types them into the ERP
- Duplicates and wrong invoices are found late, often only at payment

## Goal

A service that receives e-invoices, validates them and sends them to the ERP in a reliable way.
Errors should be visible early, not in the accounting department.

Small scope, but built like a real production system.

## Phases

### 1. Foundation
- [ ] Project setup: Java 25, Spring Boot, Maven
- [ ] Docker Compose (Postgres, Kafka)
- [ ] CI with GitHub Actions
- [ ] First ADRs

### 2. Receive & Validate
- [ ] Upload via REST
- [ ] Detect format: XRechnung (UBL, CII), ZUGFeRD
- [ ] Validation with KoSIT rules
- [ ] Validation report for each invoice

### 3. Processing
- [ ] Map to internal invoice model
- [ ] Detect duplicates (idempotency)
- [ ] Send to ERP via Kafka, outbox pattern
- [ ] Retry and dead letter queue when the ERP is not available

### 4. More Input Channels
- [ ] Mailbox (IMAP)
- [ ] SFTP

### 5. Operations
- [ ] Metrics, logs, tracing (OpenTelemetry, Grafana)
- [ ] Load test with documented results
- [ ] Security: auth, handling of sensitive data

### 6. Presentation
- [ ] README as case study
- [ ] Local demo with sample data, start with one command
- [ ] Short video (2 min)

## Not in Scope

- Sending invoices
- Approval workflows
- Connection to a real ERP (will be simulated)
- OCR for paper or image invoices
- Cloud deployment (demo runs only local)
