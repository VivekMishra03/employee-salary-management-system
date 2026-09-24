# ADR-0007 — Neon runs PostgreSQL 18, not 16; test engine realigned to 17.5

- **Status:** Accepted
- **Date:** 2026-09-21
- **Resolves:** ADR-0006 Part B (Neon verification of `btree_gist`), and the `requirements.md`
  section 11 mitigation wording that named "Verified at M1" without saying where
- **Deciders:** Manas Mishra (human), Claude Sonnet 5 (pair)

## Context

The human created a real Neon project and supplied its connection string to configure the
application. **The credential was never written to any file in this repository** — it was used
once, as a transient shell environment variable, to boot the application locally against the real
database, then discarded. This ADR records what that run found; it contains no connection details.

The verification itself was overdue and deliberate: ADR-0006 Part B left the `btree_gist` risk in
`requirements.md` section 11 explicitly **open** until checked against a real Neon instance, because
the embedded test binary (a vanilla community build running as a local superuser) is not evidence
about a managed service with its own extension allowlist and roles.

## What was verified

`./gradlew bootRun` against the real Neon connection, with `DATABASE_URL` / `DATABASE_USERNAME` /
`DATABASE_PASSWORD` supplied only as process environment variables:

```
Database: jdbc:postgresql://<neon-host>/neondb?sslmode=require&channel_binding=require (PostgreSQL 18.6)
Successfully validated 5 migrations (execution time 00:00.855s)
Migrating schema "public" to version "1 - enable extensions"
...
Successfully applied 5 migrations to schema "public", now at version v5 (execution time 00:11.871s)
Started SalaryManagementApplication in 49.562 seconds
```

Followed by `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`. The process was then
terminated (`taskkill`); nothing was left running against Neon.

**Result: V1 through V5 all applied cleanly**, including `CREATE EXTENSION btree_gist` and
`CREATE EXTENSION pg_trgm` under the `neondb_owner` role. The ADR-0006 Part B risk is **closed**:
`btree_gist` is confirmed available on Neon, not just on the embedded test engine, and the FR-3.2
exclusion constraint's precondition is real in production, not merely in theory.

## The discrepancy this surfaced

Every prior document — ADR-0001, ADR-0002, `CLAUDE.md` section 0, `requirements.md` section 8 —
assumed **PostgreSQL 16**, chosen as a stable, well-supported LTS-style target at planning time.
Neon actually provisioned **PostgreSQL 18.6** for this project. Flyway logged its own warning
during the run: *"PostgreSQL 18.6 is newer than this version of Flyway and support has not been
tested. The latest supported version of PostgreSQL is 17."*

This was not caused by anything in this codebase; it is simply what Neon's project creation
defaulted to. Nobody selected 18 on purpose, and nobody selected 16 on purpose either — it was an
unverified assumption carried since planning, and this run is what finally checked it.

## Decision

1. **Accept Neon's PostgreSQL 18** rather than asking the human to recreate the Neon project
   pinned to 16. The migrations already run cleanly against it, proven above; forcing a recreation
   for a version number with no demonstrated compatibility problem would be churn without benefit.
2. **Re-pin the embedded test engine to 17.5.0** (`build.gradle`), the newest Zonky offers — checked
   against the registry, no 18.x Windows binary exists yet (`io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64`,
   highest published version 17.5.0). This is also the newest version Flyway 11.7.2 itself declares
   official support for, so it is the ceiling both toolchains currently agree on, not an arbitrary
   choice.
3. **The one-major-version gap (17 vs. 18) is accepted, not hidden.** `SchemaMigrationTest`'s
   engine-version test now asserts `17.` and its javadoc says explicitly that this is the closest
   currently achievable match, not an exact one.

## Consequences

**Positive**
- The single largest open risk in the project (`requirements.md` section 11) is closed with a real
  observation, not an assumption — and closed in the actual target environment, not a proxy for it.
- The gap between test and production engines shrank from two major versions to one, and is now a
  known, documented, monitored quantity instead of an unexamined mismatch.
- Nothing in the five migrations uses a PostgreSQL 18-specific or PostgreSQL 16-specific feature —
  extensions, plain tables, foreign keys, CHECK constraints, GIN trigram indexes are all stable
  across 16 through 18 — which is exactly why the same SQL ran unmodified on both engines.

**Negative, accepted**
- Tests still do not run on the exact production major version. This is a real, bounded gap, not a
  theoretical one, and it will persist until Zonky publishes 18.x binaries.
- **Revisit trigger:** re-run `io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64`
  against Maven Central when planning any future milestone that adds a new extension or a
  version-sensitive SQL feature; re-pin to 18.x the moment it exists.

## Follow-up required

- `requirements.md` section 11's mitigation cell and section 8's "PostgreSQL 16" references are now
  stale in two ways (the version number, and the "Verified at M1" wording that never said where).
  Only the human may amend `requirements.md` (`CLAUDE.md` section 5); proposed wording accompanies
  this ADR for approval.
- `docs/adr/0006-entity-id-generation-and-neon-verification.md` Part B's status table should be
  marked resolved, pointing here, rather than rewritten in place.
