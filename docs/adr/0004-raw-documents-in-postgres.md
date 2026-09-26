# 4. Raw documents in Postgres

Date: 2026-09-26

## Status

Accepted

## Context

The original document of each invoice must be kept, for the validation report and for audits.
E-invoices are small, mostly less than 1 MB.

## Decision

Raw documents are stored as `bytea` in Postgres, next to the invoice data.

## Consequences

- One store and one transaction for document and invoice data.
- Backup and restore are simple.
- Not a good fit for big volumes or big files. Then an object store like S3 is the next step.
