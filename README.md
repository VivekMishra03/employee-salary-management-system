# ACME Employee Salary Management System

Web software that replaces spreadsheet-based salary management for an organisation of 10,000
employees across multiple countries, and lets an HR Manager answer questions about how the
organisation pays people.

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
| Database | PostgreSQL 18 on Neon, schema owned by Flyway ([ADR-0007](docs/adr/0007-neon-verified-postgresql-18.md)) |
| Frontend | Angular 21.2, Angular Material, zoneless |
| Backend tests | JUnit 5, AssertJ, Mockito; Zonky embedded PostgreSQL 17.5 for repository and integration tests |
| Frontend tests | Jasmine + Karma, running in a real Chrome |

Version choices are verified against their registries, not assumed — see
[`docs/adr/0002`](docs/adr/0002-verified-dependency-versions.md).

## Prerequisites

- **JDK 17** — `java -version`
- **Node 22.12+** — `node -v`. Angular 21 requires `^20.19.0 || ^22.12.0 || >=24.0.0`
- **npm 10.9.3+** — the version bundled with Node 22.18, verified against this dependency graph.
- **Google Chrome** — frontend tests run in a real browser via Karma, not a DOM simulation
  ([ADR-0004](docs/adr/0004-frontend-tests-karma-jasmine.md)).

Docker and Maven are **not** required. Repository and integration tests run against a real PostgreSQL
started from an embedded binary, so there is nothing to install or start by hand.

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

`npm start` serves on <http://localhost:4200> and proxies `/api` to a backend on `localhost:8080`
(`frontend/proxy.conf.json`). To use the deployed Render API instead, run `npm run start:remote`
(`frontend/proxy.remote.conf.json`); the proxy means the browser only talks to localhost, so no CORS is needed.
The first request after Render has been idle takes ~30 s (free-tier cold start).

## Tests

```bash
cd backend
./gradlew test          # backend unit, repository and integration tests
./gradlew build         # compile, test, package
```

```bash
cd frontend
npm test -- --watch=false
```

Tests are deterministic by rule (NFR-3): no test touches the network, and a test whose result
changes at midnight or on a leap day is treated as a defect. Time is injected via `java.time.Clock`
and fixed in tests, and randomness is seeded.

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
(NFR-4, NFR-6). Copy [`.env.example`](.env.example) to `.env` (gitignored) and fill in real values,
or export the same variables directly in your shell. Any variable left unset makes the application
refuse to start with a clear error, rather than silently connecting to nothing.

| Variable | Used by | Introduced |
|---|---|---|
| `PORT` | HTTP listen port (defaults to 8080 locally) | M0 |
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://<neon-host>/<db>?sslmode=require&channel_binding=require`. Get the pieces from your Neon dashboard's connection string — see [ADR-0007](docs/adr/0007-neon-verified-postgresql-18.md) | M1.1 |
| `DATABASE_USERNAME` | Database role. Kept separate from `DATABASE_URL` so credentials never appear in a logged connection URL | M1.1 |
| `DATABASE_PASSWORD` | Database password. Same reasoning | M1.1 |
| `JWT_SECRET` | HS256 signing key for login tokens, **at least 32 characters**. No default: the application refuses to start without it. Generate one with `openssl rand -base64 48`. See [ADR-0009](docs/adr/0009-authentication-design.md) | M2 |
| `JWT_EXPIRY_MINUTES` | Token lifetime in minutes (defaults to 60) | M2 |
| `BASE_CURRENCY` | ISO 4217 base reporting currency that salaries are converted to (defaults to `USD`) | M4 |
| `SEED_HR_PASSWORD` | Password of the HR Manager account created by the seed script (not read by the API). **No default**: at least 12 characters and at most 72 bytes, choose your own, never commit it | M5 |

## Seeding the database

The seed script ([`scripts/seed.js`](scripts/seed.js), FR-6) loads 10,000 employees with their salary
histories, plus reference data and the HR Manager account `hr.manager@acme.com`. The seed no longer
has a default password: it refuses to run until you choose one.

1. Install the script's dependencies once: `cd scripts && npm install`. The Gradle `seed` task only runs
   `node scripts/seed.js`; it does **not** run `npm install` for you, so without this step the seed fails
   with a missing-module error.
2. Set `SEED_HR_PASSWORD` (in the root `.env` or your shell) alongside the database variables above.
3. Run `cd backend && ./gradlew seed`. A missing, too short or too long password stops the run with exit
   code 1 before any database connection is made. The password is never printed or logged.
4. To re-seed a non-empty database add `-PseedArgs="--clean --confirm-wipe"` (both flags are required;
   `--clean` alone is refused before any database contact). **This wipes far more than seeded data:** it
   runs `TRUNCATE ... CASCADE` on `salary_record`, `employee`, `pay_band`, `exchange_rate`, `job_role`,
   `location`, `department`, `audit_log`, `import_error`, `import_job` and `app_user`, so the audit trail,
   import history and everything created through the UI are lost too. `node scripts/seed.js --help`
   prints the same warning.
5. To change the password of an already-seeded database, set `SEED_HR_PASSWORD` to the new value and run
   `cd scripts && node seed.js --reset-hr-password` (or `cd backend && ./gradlew seed -PseedArgs="--reset-hr-password"`).
   It updates that one account only and fails, without creating a user, if the account does not exist.

The employee and salary data is deterministic (FR-6.2): the same seed yields the same rows. The one
exception is the account's password hash, because BCrypt salts randomly, so it differs on every run
even for the same password. `--dry-run` and `--dump-sql` need no password; the dump contains a
non-working placeholder hash, so set the real password afterwards with `--reset-hr-password`.
`--dump-sql` writes `seed.sql` at the repository root; it is git-ignored because it holds 10,000
people's names, emails and salaries.

## Deploying the API to Render

[`render.yaml`](render.yaml) defines the service and [`backend/Dockerfile`](backend/Dockerfile) builds it
(Render has no native Java runtime, so it builds the image itself -- no local Docker needed).

1. Render dashboard -> **New -> Blueprint** -> select this repository.
2. Render prompts for the three values that are deliberately not in the repo: `DATABASE_URL`,
   `DATABASE_USERNAME`, `DATABASE_PASSWORD` (see the table above -- `DATABASE_URL` is the
   `jdbc:postgresql://...` form **without** credentials in it). `JWT_SECRET` is generated by Render.
3. Deploy. The health check is `/actuator/health`.

Things to know before the first deploy:

- **Flyway must find a database it can validate.** If an earlier local run already applied migrations to
  your Neon database and a migration file has since changed, startup fails with `Migration checksum
  mismatch`. The Neon database holds no real data yet, so reset it (Neon SQL editor:
  `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`) or use a fresh Neon branch, and Flyway will apply
  V1-V12 cleanly.
- **You cannot sign in yet.** No user exists until the seed (M5) runs, so a freshly deployed API starts and
  passes its health check but has no account to log in with. See "Seeding the database" above; the HR
  password is whatever you set in `SEED_HR_PASSWORD`.
- **CORS is not configured** (ADR-0009), so the Vercel frontend cannot call the API until M8/M9.
- Free plan: the service sleeps after ~15 minutes idle (first request afterwards is slow) and has 512 MB
  of RAM; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` in `render.yaml` sizes the heap for that.

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
