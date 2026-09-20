# ADR-0001 — Technology stack

- **Status:** Accepted
- **Date:** 2026-09-20
- **Deciders:** Manas Mishra (human), Claude Opus 5 (pair)

## Context

We are building a salary management system for 10,000 employees across multiple countries
(`requirements.md`). The brief fixes the frontend to Angular for a Java backend and leaves the
relational database open ("of your choice, like SQLite"). The system must be deployed and
demonstrable, at no cost.

The local machine has **Java 17, Gradle 8.5, Node 22 and Git**. It has **no Maven, no Docker and no
local Postgres client**. That constraint shapes the build and test decisions below more than any
preference does.

## Decision

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3.3, **Gradle** |
| Database | **PostgreSQL 16**, schema owned by Flyway |
| DB hosting | **Neon** free tier |
| Frontend | Angular 20 standalone components + Angular Material |
| Unit tests | JUnit 5 + AssertJ + Mockito |
| Repository tests | **Zonky embedded-postgres** (real Postgres, no Docker) |
| API hosting | **Render** free web service |
| SPA hosting | **Vercel** |

## Rationale

### PostgreSQL over SQLite
The product's core value is analytics (FR-4): medians, percentiles, grouped aggregation, pay-band
comparison, gender gap. That work leans on SQL that SQLite does not do well.

- **`NUMERIC` is a real type.** SQLite has no native decimal; money would be stored as text or float.
  NFR-2 makes float unacceptable for compensation data.
- **`percentile_cont`** gives us median and quartiles (FR-4.1) in one query. SQLite has no percentile
  aggregate; we would hand-roll it or pull rows into memory, violating FR-4.7.
- **`EXCLUDE USING gist`** enforces the non-overlapping salary interval invariant (FR-3.2) *in the
  database*, so no code path can corrupt the history. This is the single strongest integrity guarantee
  in the design and SQLite cannot express it.
- **`JSONB`** stores audit before/after states (FR-3.7) without a serialisation layer.
- **Concurrent writes.** SQLite serialises writers. Not fatal for one HR Manager, but a poor fit for a
  system of record.

SQLite's genuine advantages — zero setup, fast tests — are largely neutralised by embedded Postgres,
which gives us a real engine without Docker.

### Neon over Supabase or a self-hosted instance
Free forever (0.5 GB, no card), plain Postgres 16 with a standard connection string, and separated
storage/compute so it suspends when idle without losing data. Supabase bundles auth, storage and
realtime that we would not use; Neon is the leaner fit for "just a Postgres".

### Zonky embedded-postgres over Testcontainers or H2
Testcontainers is the usual answer and needs Docker, which is not installed here. H2 in Postgres
compatibility mode runs fast but would not execute the SQL we actually ship — `percentile_cont`,
`EXCLUDE` constraints and `daterange` are exactly the features H2 fakes or lacks, and those are the
features carrying our hardest invariants. A repository test that passes on H2 and fails in production
is worse than no repository test.

Zonky downloads a real Postgres binary and runs it as a subprocess. Cost: a one-off download and
~3–5 s startup per suite. Mitigated by keeping the bulk of tests as pure unit tests with no database
(`requirements.md` §9) and starting one instance per run.

### Gradle over Maven
Gradle is installed; Maven is not. The wrapper is committed so the build is reproducible anywhere.

### Render + Vercel
Both free without a card. Render builds remotely from the repo, so the absence of local Docker is not
an obstacle. Vercel serves the Angular bundle from a CDN.

**Accepted limitation:** Render's free tier sleeps after ~15 minutes idle; the first request then
takes roughly 30 seconds. We accept this for free-forever hosting, document it in the README, and warm
the instance before recording the demo. Railway avoids the cold start but runs on trial credit, so the
demo link would expire — a worse outcome for an assessment that will be reviewed later.

## Consequences

**Positive**
- Analytics are expressed as SQL the database is good at, keeping FR-4.7 honest.
- The most important data invariant is enforced by the database, not by hope.
- Tests run against the same engine that runs in production.
- Zero cost, no credit card, deployable from this machine as it is configured.

**Negative**
- Postgres is heavier to set up locally than a SQLite file. Mitigated by Neon for dev and embedded
  Postgres for tests, so no local install is needed at all.
- Embedded Postgres adds seconds to the suite versus H2.
- Cold starts on the free API tier affect first-request latency.

**Revisit if:** the dataset grows past ~1M employees (revisit indexing and materialised views), or
the demo needs guaranteed sub-second first response (move off a sleeping free tier).

## Alternatives rejected

- **SQLite + Hibernate Community Dialects** — fastest to start, but forces analytics into application
  memory and cannot enforce the interval invariant. Rejected on FR-4 and FR-3.2.
- **MySQL 8** — capable, but weaker than Postgres for `EXCLUDE` constraints and JSON, with no free
  managed tier as clean as Neon's.
- **H2 for everything** — a test database running in production is not a defensible choice for a
  system of record holding compensation data.
