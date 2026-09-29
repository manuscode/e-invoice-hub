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
- [x] Project setup: Java 25, Spring Boot, Maven
- [x] Docker Compose (Postgres, Kafka)
- [x] CI with GitHub Actions
- [x] First ADRs

### 2. Receive & Validate
- [x] Upload via REST
- [x] Detect format: XRechnung (UBL, CII), ZUGFeRD
- [x] Validation with KoSIT rules
- [x] Validation report for each invoice

### 3. Processing
- [x] Map to internal invoice model
- [x] Detect duplicates (idempotency)
- [x] Send to ERP via Kafka, outbox pattern
- [x] Retry and dead letter queue when the ERP is not available

### 4. More Input Channels
- [x] Mailbox (IMAP)
- [x] SFTP

### 5. Operations
- [x] Metrics, logs, tracing (OpenTelemetry, Grafana)
- [x] Load test with documented results
- [x] Security: auth, handling of sensitive data

### 6. Presentation
- [x] README as case study
- [x] Local demo with sample data, start with one command
- [ ] Short video (2 min)

## Not in Scope

- Sending invoices
- Approval workflows
- Connection to a real ERP (will be simulated)
- OCR for paper or image invoices
- Cloud deployment (demo runs only local)
