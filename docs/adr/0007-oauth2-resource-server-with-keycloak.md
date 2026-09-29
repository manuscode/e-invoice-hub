# 7. OAuth2 resource server with Keycloak

Date: 2026-09-29

## Status

Accepted

## Context

The REST API receives and returns invoices with bank details and personal data. Only known systems may use it.
Clients are other systems (e.g. a supplier portal or the accounting), not people in a browser.
Some may only upload, others may only read.

Options:

- API keys, checked by the hub
- Basic auth with users in the hub
- OAuth2 with JWT from an identity provider

## Decision

The hub is an OAuth2 resource server and accepts only JWTs from Keycloak (Spring Security, `spring-boot-starter-security-oauth2-resource-server`).

- Clients get tokens with the client credentials flow. The hub never sees client secrets.
- The hub checks signature, issuer, expiry and audience `invoice-hub`. Keycloak adds the audience with a mapper per client.
- Two realm roles: `invoice-uploader` for `POST /api/invoices`, `invoice-reader` for `GET /api/invoices/**`.
  Spring Boot maps them from the claim `realm_access.roles`, there is no own converter.
- Everything else is denied, only `/actuator/health` is open.
- Stateless, no session and no CSRF protection, because the token is sent in a header and not in a cookie.

Locally Keycloak runs in `docker-compose.yml` with the demo realm from `docker/keycloak`.
Its clients `demo-uploader` and `demo-reader` have fixed secrets, only for local use.

## Consequences

- Clients, secrets and roles are managed in Keycloak. Adding a client or rotating a secret needs no deployment of the hub.
- Any OIDC provider works, only issuer and role claim must be configured.
- The hub needs Keycloak only for the public keys, not for each request. Keys are cached.
- A token stays valid until it expires, even if its client is disabled. Keycloak's default lifetime is 5 minutes.
- Tests use mocked tokens with MockMvc. `SecurityIntegrationTest` runs with a real Keycloak and is slower.
