# 6. Duplicate detection

Date: 2026-09-27

## Status

Accepted

## Context

The same invoice can reach the hub more than once:

- A channel retries after a timeout and sends the same file again.
- A supplier sends the same invoice by mail and by SFTP, or as UBL and as ZUGFeRD PDF.

Retries must be safe. Real duplicates must be visible, so nobody pays an invoice twice, but must not be silently dropped.
Uploads run in parallel, so a check in Java alone ("look up, then insert") is not enough.

## Decision

Two levels, both enforced by the database:

1. **Same document:** The SHA-256 of the raw document is stored with a unique constraint.
   The insert uses `on conflict (content_sha256) do nothing`. If nothing was inserted, the existing invoice is returned,
   via REST with `200` instead of `201`. The document is not validated again.
2. **Same invoice, other document:** After validation the invoice gets a business key: seller VAT ID + invoice number.
   Without seller VAT ID the seller name is used. Both are normalized (whitespace, case).
   A partial unique index allows only one original per key (`where duplicate_of is null`).
   A later invoice with the same key gets status `DUPLICATE` and a reference to the original.
   If two invoices with the same key are validated at the same time, the unique index rejects the second one,
   which is then stored as `DUPLICATE` of the first.

Only valid invoices get a business key. Rejected invoices never block a later, corrected invoice.

## Consequences

- Retries from any channel are idempotent, also in parallel.
- Duplicates stay in the system with their document and can be checked by a person.
- A corrected invoice with the same number is a `DUPLICATE`. Correct is a credit note and a new invoice number anyway.
- The seller name fallback misses duplicates if the name is written differently, e.g. "ACME GmbH" and "ACME G.m.b.H.".
- A document that failed for technical reasons after it was stored stays `RECEIVED`. Sending it again returns this invoice
  and does not retry validation.
