# 5. XRechnung configuration at build time

Date: 2026-09-26

## Status

Accepted

## Context

The KoSIT validator needs the official XRechnung configuration (schemas, Schematron rules, report XSL).
It is not on Maven Central, only as zip on [GitHub](https://github.com/itplr-kosit/validator-configuration-xrechnung/releases).
A new release comes about twice a year.

Options:

- Commit the unpacked files into the repo
- Download the zip during the build

## Decision

The Maven build downloads the zip in `generate-resources` and unpacks it into the application.
Version and SHA-256 checksum are set in `invoice-hub/pom.xml`.

KoSIT only loads schemas from `file:` URLs, not from inside a jar.
So on startup the configuration is copied to a temporary directory (`XRechnungConfiguration`).

## Consequences

- No third-party files in the repo. An update is a change of version and checksum in one place.
- The checksum makes sure the build uses exactly the tested configuration.
- The first build needs access to GitHub. After that the zip is cached in the local Maven repository.
- The jar stays self-contained, no extra files next to it.
