# ADR-0002 — Verified dependency versions, and Angular 21 over Angular 22

- **Status:** Accepted
- **Date:** 2026-09-20
- **Supersedes:** the indicative version numbers in ADR-0001 and `CLAUDE.md` §0
- **Deciders:** Manas Mishra (human), Claude Opus 5 (pair)

## Context

ADR-0001 and `CLAUDE.md` §0 named "Spring Boot 3.3" and "Angular 20". Those were written during
planning, before anything was checked. `CLAUDE.md` §4.3 forbids relying on an unverified library
version, so every version was resolved against its real registry before the first build file was
written.

Two of the planning assumptions turned out to be wrong, and one of them was a genuine blocker.

## What was verified

Queried against Maven Central and the npm registry on 2026-09-20:

| Dependency | Planned | Verified available | Chosen |
|---|---|---|---|
| Spring Boot | 3.3 | 3.5.3 latest stable | **3.5.3** |
| `io.spring.dependency-management` | — | 1.1.7 | **1.1.7** |
| Angular / CLI | 20 | 22.1.8 latest | **21.2.24** (see below) |
| Angular Material | — | 22.1.7 | matching 21.x |
| PostgreSQL JDBC | — | 42.7.7 | **42.7.7** |
| Flyway (postgres) | — | 11.8.2 | managed by the Spring Boot BOM |
| jjwt | — | 0.12.6 | **0.12.6** |
| AssertJ | — | 3.27.3 (4.0.0-M1 is a milestone) | **3.27.3**, stable only |
| Zonky `embedded-database-spring-test` | — | 2.6.0 | **2.6.0** |
| OpenCSV | — | 5.10 | **5.10** |

### The blocker: Angular 22 cannot run on this machine

`@angular/cli@22.1.8` declares `engines.node = ^22.22.3 || ^24.15.0 || >=26.0.0`.
The installed Node is **22.18.0**, which does **not** satisfy `^22.22.3`.

`@angular/cli@21.2.24` declares `engines.node = ^20.19.0 || ^22.12.0 || >=24.0.0`, which
22.18.0 does satisfy.

### The other risk closed: embedded Postgres on Windows

ADR-0001 chose Zonky embedded-postgres because Docker is unavailable, but treated Windows support as
an assumption. It is now confirmed: `io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64`
publishes 201 artifacts including **Postgres 16.9.0**, matching our target major version. The
no-Docker test strategy in `requirements.md` §9 is viable as specified.

## Decision

1. **Spring Boot 3.5.3** — current stable, Java 17 compatible, and compatible with the installed
   Gradle 8.5 (Spring Boot 3.5 requires Gradle 7.6.4+ or 8.4+).
2. **Angular 21.2.24**, not 22. It is the newest Angular that runs on the installed Node.
3. **Stable releases only.** AssertJ 4.0.0-M1 was available and rejected; a milestone build in a test
   framework trades correctness confidence for nothing we need.
4. Dependencies are added **at the milestone that first needs them**, not up front. M0 ships web,
   validation and actuator only; JPA/Flyway/Postgres arrive in M1 with the schema, jjwt in M2,
   OpenCSV in M7. This keeps the commit history an honest record of how the system grew.

## Rationale for Angular 21 over upgrading Node

Angular 21 and 22 are equivalent for everything in `requirements.md` — standalone components,
signals, the built-in control flow and Angular Material are all present in 21. Upgrading Node to
22.22.3+ to gain Angular 22 would change a working global toolchain for no feature we use, and risks
breaking other projects on this machine for zero benefit to this one.

Reversible: if Node is upgraded later, `ng update` moves 21 → 22 without touching application code.

## Consequences

**Positive**
- Every version in the build is one that was confirmed to exist and to be runnable here.
- The largest technical risk in ADR-0001 (embedded Postgres without Docker, on Windows) is closed
  with evidence rather than optimism.
- Incremental dependency introduction keeps each commit scoped to one change.

**Negative**
- The project runs one Angular major behind latest. Accepted, and documented here so a reviewer sees
  it was a constrained decision rather than an oversight.

**Follow-up:** `CLAUDE.md` §0 has been corrected to the verified versions. The planning-time numbers in
ADR-0001 are left as written — an ADR records what was decided at the time, and rewriting history
would defeat its purpose. This ADR supersedes them.
