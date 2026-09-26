# 2. Modular monolith

Date: 2026-09-26

## Status

Accepted

## Context

The hub receives, validates and delivers invoices. The scope is small and one team works on it.
Microservices would bring more deployments, more network calls and distributed transactions.

## Decision

One Spring Boot service, split into modules by business area: `intake`, `validation`, `invoice`, `delivery`.
Spring Modulith checks the module borders in a test (`ModularityTest`).

## Consequences

- One deployment, one database, local transactions.
- Module borders are checked on every build, not only by convention.
- Modules can't be scaled on their own. If needed, a module can be moved to its own service along its border.
