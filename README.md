# ACME Employee Salary Management System

Web software that replaces spreadsheet-based salary management for an organisation of 10,000
employees across multiple countries, and lets an HR Manager answer questions about how the
organisation pays people.

> **Status: in development.** Milestone M0 (foundation) is complete. See
> [`requirements.md`](requirements.md) §10 for the milestone plan and what is built so far.
> Claims in this README describe what exists today — nothing here is aspirational.

## Documentation

| Document | Purpose |
|---|---|
| [`requirements.md`](requirements.md) | The specification — goal, scope, out-of-scope reasoning, data model, milestones |
| [`CLAUDE.md`](CLAUDE.md) | Engineering rules: TDD, review gates, commit discipline, anti-hallucination |
| [`docs/WORKFLOW.md`](docs/WORKFLOW.md) | How work moves through the review and feedback loop |
| [`docs/adr/`](docs/adr/) | Architecture decision records |

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.5.3, Gradle (wrapper included) |
| Database | PostgreSQL 16, schema owned by Flyway *(introduced in M1)* |
| Frontend | Angular 21.2, Angular Material *(components introduced in M8)* |
| Backend tests | JUnit 5, AssertJ *(Mockito and Zonky embedded Postgres introduced in M1)* |
| Frontend tests | Jasmine + Karma, running in a real Chrome |

Version choices are verified against their registries, not assumed — see
[`docs/adr/0002`](docs/adr/0002-verified-dependency-versions.md).

## Prerequisites

- **JDK 17** — `java -version`
- **Node 22.12+** — `node -v`. Angular 21 requires `^20.19.0 || ^22.12.0 || >=24.0.0`
- **npm 10.9.3+** — the version bundled with Node 22.18, verified against this dependency graph.
- **Google Chrome** — frontend tests run in a real browser via Karma, not a DOM simulation
  ([ADR-0004](docs/adr/0004-frontend-tests-karma-jasmine.md)).

Docker and Maven are **not** required. *(From M1, repository tests will run a real Postgres via an
embedded binary, so there will still be nothing to install or start by hand.)*

## Running

### Backend

```bash
cd backend
./gradlew bootRun
```

Serves on `http://localhost:8080`, or on `$PORT` when the environment sets one (NFR-6).
Health check: `http://localhost:8080/actuator/health`.

### Frontend

```bash
cd frontend
npm start
```

## Tests

```bash
cd backend
./gradlew test          # backend unit tests (repository tests added in M1)
./gradlew build         # compile, test, package
```

```bash
cd frontend
npm test -- --watch=false
```

Tests are deterministic by rule (NFR-3): no test touches the network, and a test whose result
changes at midnight or on a leap day is treated as a defect. From M1, where domain logic starts to
depend on dates, time is injected via `java.time.Clock` and fixed in tests, and randomness is
seeded.

## Project layout

```
.
├── backend/                  # Spring Boot API
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew, gradlew.bat, gradle/
│   └── src/
│       ├── main/java/com/acme/salary/
│       └── test/java/com/acme/salary/
├── frontend/                 # Angular 21 SPA
├── .claude/agents/           # review-gate agent definitions
├── docs/
│   ├── adr/                  # architecture decision records
│   └── WORKFLOW.md           # review and feedback loop
├── .github/workflows/
│   ├── ci-backend.yml        # runs only on backend/** changes
│   └── ci-frontend.yml       # runs only on frontend/** changes
├── requirements.md           # the specification
└── CLAUDE.md                 # engineering rules
```

## Configuration

All environment-specific values are supplied as environment variables; nothing secret is committed
(NFR-4, NFR-6). The variables required at each milestone are documented as they are introduced.

| Variable | Used by | Introduced |
|---|---|---|
| `PORT` | HTTP listen port (defaults to 8080 locally) | M0 |

## Development process

This project is built under an explicit set of engineering rules, because the process is part of the
deliverable:

- **Test-driven.** Every behaviour starts with a failing test, and the failure is captured as
  evidence before the implementation is written.
- **Two review gates per task.** A requirements analyst checks the change against the spec (no less,
  and no more); a separate code-quality reviewer checks correctness, tests and security. Both are
  fresh reviewers that did not write the code.
- **Human approval before every commit.**
- **Incremental commits**, each scoped to one change and tagged with the requirement ID it serves.

See [`docs/WORKFLOW.md`](docs/WORKFLOW.md) for the full loop.
