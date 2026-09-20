# Requirements — ACME Employee Salary Management System

| | |
|---|---|
| **Version** | 1.0 |
| **Status** | Baseline for implementation |
| **Persona** | HR Manager, ACME Corp |
| **Date** | 2026-09-20 |

> This document is the **single source of truth** for what gets built. Code is written to satisfy
> requirement IDs defined here (`FR-*`, `NFR-*`). No feature is implemented that does not trace to an
> ID in this document; no ID is added or changed without a decision recorded in `docs/adr/` and
> approved by the human reviewer. See `CLAUDE.md` for how this is enforced during development.

---

# Part 1 — Requirements (the one-pager)

## 1. Goal

ACME's HR team manages compensation for **10,000 employees across multiple countries** in
spreadsheets. Excel provides storage but not *answers*: there is no record of why pay changed, no
safe concurrent editing, no validation, and no way to compare pay across currencies without
hand-built lookups that silently rot.

Replace the spreadsheets with web software that lets one HR Manager:

1. **Maintain** accurate salary data for 10,000 employees as a system of record, and
2. **Answer questions about how the organisation pays people** — by department, country, job level
   and gender — in seconds rather than an afternoon of pivot tables.

Success is judged on (2). A CRUD app that stores salaries but cannot answer *"are our Berlin
engineers underpaid relative to band?"* has not solved the stated problem.

## 2. Scope — what we are building

### FR-1 — Authentication and access control
- **FR-1.1** HR Manager signs in with email and password; credentials are BCrypt-hashed, never stored or logged in plaintext.
- **FR-1.2** Every API endpoint except login and health checks requires a valid JWT bearer token.
- **FR-1.3** Expiry is enforced server-side; the UI redirects to login on `401` and never renders salary data to an unauthenticated user.

### FR-2 — Employee directory (system of record)
- **FR-2.1** Create, read, update and soft-delete (deactivate) an employee.
- **FR-2.2** Paginated, sorted employee list that stays responsive at 10,000 rows — **server-side** pagination, sorting and filtering. The client never receives the full dataset.
- **FR-2.3** Free-text search across name, employee code and email.
- **FR-2.4** Filter by department, country, employment status, employment type and job level, combinable.
- **FR-2.5** Employee detail view showing current compensation, full salary history, manager and direct reports.
- **FR-2.6** Concurrent edits to the same employee are detected and rejected rather than silently overwriting (optimistic locking) — the precise failure mode Excel cannot prevent.

### FR-3 — Compensation and salary history
- **FR-3.1** Salary is **temporal**, not a column on the employee. Each employee has an ordered history of salary records with effective dates.
- **FR-3.2** Recording a raise closes the previous record and opens a new one atomically. Intervals for one employee never overlap and never leave gaps after the hire date.
- **FR-3.3** Every salary change carries a **reason** (new hire, merit increase, promotion, market adjustment, role change, correction) and optional notes. This is the "why" that Excel loses.
- **FR-3.4** Salaries are stored in the employee's **local currency** with a pay frequency (annual / monthly / hourly). The system derives an annualised amount and an amount converted to the organisation's base currency (USD) for comparison.
- **FR-3.5** Currency conversion uses a stored, dated exchange-rate table — never a hardcoded constant, never a live third-party call at request time. Historical comparisons use the rate effective at that time.
- **FR-3.6** Back-dated and future-dated salary changes are supported; the "current" salary is the record effective today.
- **FR-3.7** Every salary mutation is written to an immutable audit log recording who changed what, when, and the before/after values.

### FR-4 — Pay analytics ("how do we pay people?")
A dashboard answering, for any filtered slice of the org:
- **FR-4.1** Headcount, total annualised payroll cost, and mean / median / p25 / p75 base pay in base currency.
- **FR-4.2** Pay comparison grouped by department, by country and by job level, with headcount and median per group.
- **FR-4.3** Salary distribution histogram, to expose outliers and clustering.
- **FR-4.4** **Pay-band adherence** — each employee's compa-ratio against the band for their role and location, plus counts below / within / above band. This is the actionable output: it names people, not just averages.
- **FR-4.5** **Gender pay gap** by department and by job level (mean and median), reported as a percentage alongside headcount, and suppressed for groups below a minimum size to avoid re-identifying individuals.
- **FR-4.6** Compensation change over time — total payroll and average increase percentage by period.
- **FR-4.7** All analytics respect the active filters and are computed in the database, not in application memory.

### FR-5 — Bulk import and export (the Excel offramp)
- **FR-5.1** Upload a CSV of employees and/or salary changes.
- **FR-5.2** Every row is validated before anything is written: required fields, data types, currency codes, date sanity, duplicate employee codes, unknown departments or locations.
- **FR-5.3** Import is **transactional per file** by default, so a bad file cannot leave the org half-updated; the result reports per-row errors with row number, column and a human-readable message.
- **FR-5.4** A downloadable error report and a CSV template, so an HR user can fix and re-upload without guessing the schema.
- **FR-5.5** Export the current filtered view to CSV — HR must never feel trapped in the tool.
- **FR-5.6** Import jobs are recorded with status and row counts, so a failed upload is diagnosable after the fact.

### FR-6 — Seed data
- **FR-6.1** A repeatable seed script generates **10,000 employees** with deterministic data across multiple countries, departments and job levels, with realistic pay distributions, multi-record salary histories, exchange rates and pay bands.
- **FR-6.2** Seeding is deterministic — the same seed yields the same dataset, so demos, screenshots and performance numbers are reproducible.

## 3. Non-functional requirements

- **NFR-1 Performance.** Employee list p95 < 500 ms and analytics endpoints p95 < 1 s at 10,000 employees / ~35,000 salary records, measured against the seeded dataset rather than asserted.
- **NFR-2 Correctness of money.** All monetary values use `BigDecimal` / `NUMERIC(15,2)`. Floating-point types are banned for money anywhere in the stack. Rounding is explicit (HALF_UP) and asserted in tests.
- **NFR-3 Test quality.** Core domain logic — temporal salary transitions, currency normalisation, compa-ratio, statistics, import validation — is covered by fast, deterministic, isolated tests. No test depends on wall-clock `now()`, network access, or test execution order. Target ≥80% line coverage on domain and service packages, treated as a floor rather than a goal.
- **NFR-4 Security.** JWT auth, BCrypt hashing, parameterised queries only, no salary PII in logs, secrets from environment variables and never committed, CORS restricted to the deployed frontend origin.
- **NFR-5 Maintainability.** Layered architecture with one-way dependencies; DTOs at the API boundary so JPA entities are never serialised directly; schema owned by versioned Flyway migrations, never Hibernate auto-DDL.
- **NFR-6 Deployability.** Twelve-factor configuration. The application starts from environment variables alone, with no code change between local and production.
- **NFR-7 Auditability.** Salary data is financial PII; every mutation is attributable to a user and a timestamp.
- **NFR-8 Traceability.** Every commit message and every test references the requirement ID it serves.

## 4. Explicitly out of scope — and why

| Excluded | Reasoning |
|---|---|
| **Payroll processing, payslips, tax and statutory deductions** | The brief asks us to *manage salary data and answer questions about it*. Payroll execution is a regulated, country-specific domain (tax tables, social security, filings) that would consume the entire exercise and demonstrate compliance research rather than engineering judgment. We manage compensation; we do not disburse it. |
| **Multi-tenancy** | One organisation — ACME — is specified. Tenant isolation would add a discriminator to every table, query and test for zero benefit to the stated user. Deliberately deferred; the schema keeps it addable. |
| **Employee and line-manager self-service portals** | The brief names exactly one persona. Extra roles multiply screens, permission rules and the test matrix while answering a question nobody asked. The manager relationship is already modelled, so this is purely additive later. |
| **Salary review / approval workflow** | Proposing, budgeting and approving raises is a second system with its own state machine. Including it risks two half-finished features instead of one complete one. The `change_reason` field on every salary record is the seam it would attach to. |
| **Live FX rate integration** | An outbound call would make tests non-deterministic and the app fail when a third party is down — both directly contrary to NFR-3. Rates live in a seeded, dated table with a documented refresh path. |
| **Benefits, equity vesting, PTO, performance reviews** | Adjacent HR domains, each large. We model a bonus *target percentage* so total-compensation analytics work, and stop there. |
| **Real-time collaboration / websockets** | A single HR Manager. Optimistic locking (FR-2.6) is the correct and far cheaper answer to the actual concurrency risk. |
| **Mobile-native apps** | Desktop-first responsive web matches how compensation work is actually done: a large screen and dense tables. |
| **Caching layers, read replicas, sharding, horizontal scaling** | 10,000 employees is a *small* dataset. A single Postgres instance with correct indexes answers every query in this spec in milliseconds. Adding Redis or replicas would be architecture theatre; the honest decision is to prove the simple design is fast enough (NFR-1) and document where it would break. |

## 5. Assumptions

1. One organisation, one base reporting currency (USD), configurable.
2. Exchange rates are refreshed out-of-band; staleness is acceptable for analytical comparison and is surfaced with an "as of" date in the UI rather than hidden.
3. Gender is an optional, self-declared field including *prefer not to say*, used only for aggregate gap reporting (FR-4.5) and never shown on the directory list.
4. Salary records are corrected by superseding rows, never by hard deletion — the history *is* the audit trail.
5. 10,000 employees is the design point, not 10 million. Decisions optimise for clarity at this scale, and are documented where they would change at 100×.

---

# Part 2 — Development plan

## 6. Entities and data model

The data model is the foundation of this system, so it is designed before any endpoint or screen.
The central modelling decision is that **compensation is temporal**: salary is not an attribute of an
employee but a sequence of dated records. Everything in FR-3 and FR-4 follows from that choice.

### 6.1 Entity-relationship overview

```
                        ┌──────────────┐
                        │  app_user    │  authenticates (FR-1)
                        └──────┬───────┘
                               │ actor
                               ▼
┌────────────┐  parent  ┌──────────────┐        ┌──────────────┐
│ department │◄────────┐│              │        │  audit_log   │
└─────┬──────┘         ││              │        └──────────────┘
      │                │└──────────────┘
      │ belongs to
      ▼
┌──────────────────┐   manager (self-ref)
│    employee      │◄──────────┐
│                  │───────────┘
└───┬────┬─────┬───┘
    │    │     │
    │    │     └──────────────► ┌──────────────┐
    │    │        works at      │   location   │──► currency_code
    │    │                      └──────┬───────┘
    │    └─────────────────────► ┌─────┴────────┐
    │           holds role       │   job_role   │
    │                            └─────┬────────┘
    │                                  │
    │ 1..*                        ┌────┴──────────┐
    ▼                             │   pay_band    │ (job_role × location)
┌──────────────────┐              └───────────────┘
│  salary_record   │  temporal history (FR-3.1)
└──────────────────┘
         │ normalised via
         ▼
┌──────────────────┐        ┌──────────────┐      ┌──────────────┐
│  exchange_rate   │        │  import_job  │─1..*─│ import_error │
└──────────────────┘        └──────────────┘      └──────────────┘
```

### 6.2 Entity definitions

#### `employee` — a person on the payroll
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` PK | Surrogate key |
| `employee_code` | `VARCHAR(20)` UNIQUE NOT NULL | Human-facing natural key, e.g. `ACME-004821`. The key HR uses in CSVs. |
| `first_name`, `last_name` | `VARCHAR(100)` NOT NULL | |
| `email` | `VARCHAR(255)` UNIQUE NOT NULL | Work email |
| `gender` | `VARCHAR(20)` NULL | `FEMALE` / `MALE` / `NON_BINARY` / `PREFER_NOT_TO_SAY`. Nullable by design; used only for FR-4.5 |
| `hire_date` | `DATE` NOT NULL | Lower bound for all salary intervals |
| `termination_date` | `DATE` NULL | Set when status becomes `TERMINATED` |
| `employment_status` | `VARCHAR(20)` NOT NULL | `ACTIVE` / `ON_LEAVE` / `TERMINATED` — drives the soft delete in FR-2.1 |
| `employment_type` | `VARCHAR(20)` NOT NULL | `FULL_TIME` / `PART_TIME` / `CONTRACT` |
| `fte_ratio` | `NUMERIC(4,3)` NOT NULL DEFAULT 1.000 | Part-timers must not distort pay comparisons; analytics compare FTE-adjusted pay |
| `department_id` | FK → `department` NOT NULL | |
| `job_role_id` | FK → `job_role` NOT NULL | |
| `location_id` | FK → `location` NOT NULL | Determines local currency and pay band |
| `manager_id` | FK → `employee` NULL | Self-referencing; `NULL` for the CEO |
| `created_at`, `updated_at` | `TIMESTAMPTZ` NOT NULL | |
| `version` | `BIGINT` NOT NULL | JPA `@Version` — implements FR-2.6 |

**Invariants:** `termination_date >= hire_date`; `manager_id <> id`; `employment_status = 'TERMINATED'` iff `termination_date IS NOT NULL`.

**Indexes:** `(department_id)`, `(location_id)`, `(job_role_id)`, `(employment_status)`, `(manager_id)`, and a `pg_trgm` GIN index over `first_name || last_name || employee_code || email` for FR-2.3 search at 10k rows.

#### `salary_record` — the temporal heart of the system
| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` PK | |
| `employee_id` | FK → `employee` NOT NULL | |
| `effective_from` | `DATE` NOT NULL | |
| `effective_to` | `DATE` NULL | `NULL` means "still in force" (FR-3.6) |
| `base_amount` | `NUMERIC(15,2)` NOT NULL CHECK > 0 | In local currency, as entered (FR-3.4) |
| `currency_code` | `CHAR(3)` NOT NULL | ISO 4217 |
| `pay_frequency` | `VARCHAR(10)` NOT NULL | `ANNUAL` / `MONTHLY` / `HOURLY` |
| `annualised_amount` | `NUMERIC(15,2)` NOT NULL | Derived on write: monthly × 12, hourly × 2080. Stored so analytics need no per-row arithmetic |
| `annualised_amount_base_ccy` | `NUMERIC(15,2)` NOT NULL | Converted to USD at the rate effective on `effective_from` (FR-3.5). Stored to keep FR-4 queries pure aggregation |
| `target_bonus_pct` | `NUMERIC(5,2)` NOT NULL DEFAULT 0 | Enables total-compensation analytics without a second entity |
| `change_reason` | `VARCHAR(30)` NOT NULL | `NEW_HIRE` / `MERIT_INCREASE` / `PROMOTION` / `MARKET_ADJUSTMENT` / `ROLE_CHANGE` / `DEMOTION` / `CORRECTION` (FR-3.3) |
| `notes` | `TEXT` NULL | |
| `created_by` | FK → `app_user` NOT NULL | |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |

**The critical invariant (FR-3.2):** for a given `employee_id`, the `[effective_from, effective_to)`
intervals must not overlap. This is enforced in **two** places, deliberately:
1. In the domain service, which closes the prior record when a new one is opened — so the rule is
   unit-testable without a database, and
2. In Postgres with `EXCLUDE USING gist (employee_id WITH =, daterange(effective_from, effective_to) WITH &&)`
   (requires the `btree_gist` extension) — so no code path, migration or manual fix can corrupt it.

Two derived columns (`annualised_amount`, `annualised_amount_base_ccy`) are stored rather than
computed at read time. This is a conscious denormalisation: FR-4 runs aggregations across ~10,000
current salary rows and storing the normalised value turns every analytics query into a plain
`GROUP BY`. The cost is that a retro-active exchange-rate correction requires a backfill — an
acceptable and documented trade, recorded in `docs/adr/`.

**Indexes:** `(employee_id, effective_from DESC)`; partial index `(employee_id) WHERE effective_to IS NULL` for the hot "current salary" lookup.

#### `v_current_salary` — a view, not a table
A SQL view exposing exactly one row per employee (`effective_to IS NULL`). Every analytics query in
FR-4 reads from this view, which keeps the temporal filter in one place instead of repeated across a
dozen query strings.

#### `department`
`id`, `code` UNIQUE, `name`, `parent_department_id` (self-ref, supports an org hierarchy), `cost_center`.

#### `location`
`id`, `country_code` `CHAR(2)` (ISO 3166-1 alpha-2), `country_name`, `city`, `currency_code` `CHAR(3)`, UNIQUE `(country_code, city)`. The link between an employee and their local currency.

#### `job_role`
`id`, `title`, `job_family` (e.g. Engineering, Sales), `job_level` (`L1`–`L8`), UNIQUE `(title, job_level)`. Job level is the axis FR-4.2 and FR-4.5 group by; it must be a real column, not parsed out of a title string.

#### `pay_band` — makes FR-4.4 possible
`id`, `job_role_id` FK, `location_id` FK, `currency_code`, `min_amount`, `mid_amount`, `max_amount` (`NUMERIC(15,2)`), `effective_from`, `effective_to`. UNIQUE `(job_role_id, location_id, effective_from)`. CHECK `min <= mid <= max`.
Compa-ratio = `annualised_amount / mid_amount`. Bands are per role **and** location because a band is meaningless without a market.

#### `exchange_rate`
`id`, `from_currency` `CHAR(3)`, `to_currency` `CHAR(3)`, `rate` `NUMERIC(18,8)`, `effective_date` `DATE`, `source` `VARCHAR(50)`. UNIQUE `(from_currency, to_currency, effective_date)`. Lookup takes the most recent rate on or before a given date (FR-3.5). `NUMERIC(18,8)` because FX precision differs from money precision.

#### `app_user`
`id`, `email` UNIQUE, `password_hash` (BCrypt), `full_name`, `role` (`HR_MANAGER`), `enabled`, `created_at`. Kept separate from `employee`: an HR system operator is not necessarily an employee record, and conflating authentication with HR data is a mistake to avoid from the start.

#### `audit_log` (FR-3.7)
`id`, `entity_type`, `entity_id`, `action` (`CREATE`/`UPDATE`/`DELETE`), `actor_user_id`, `changed_at`, `before_state` `JSONB`, `after_state` `JSONB`. Append-only; no update or delete path exists in code.

#### `import_job` and `import_error` (FR-5.6)
- `import_job`: `id`, `filename`, `import_type`, `status` (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`), `total_rows`, `success_rows`, `failed_rows`, `started_at`, `finished_at`, `created_by`.
- `import_error`: `id`, `import_job_id` FK, `row_number`, `column_name`, `error_code`, `message`, `raw_row`. Drives the downloadable error report in FR-5.4.

### 6.3 Modelling decisions worth defending

1. **Temporal salary records over a `current_salary` column.** Without this, FR-3.3 and FR-4.6 are
   impossible and the system repeats Excel's core failure — it knows what people are paid but not why
   or since when.
2. **Local currency stored alongside a normalised base-currency amount.** Storing only USD loses what
   HR actually agreed with the employee; storing only local makes every cross-country query a join
   against dated FX. We store both and derive the second on write.
3. **`NUMERIC`, never `DOUBLE PRECISION`.** Binary floating point cannot represent `0.1`; aggregating
   10,000 salaries in `double` produces drift that is indefensible in a compensation system (NFR-2).
4. **Soft delete via `employment_status`.** A terminated employee's salary history must remain for
   historical analytics; a hard delete would silently rewrite last year's payroll totals.
5. **FTE ratio as a first-class column.** Comparing a part-timer's nominal salary against a full-time
   band produces false "underpaid" signals — a real HR-analytics bug, avoided by design.
6. **Pay bands keyed by role *and* location.** A single global band for "Senior Engineer" would flag
   every employee outside the US as out-of-band, making FR-4.4 useless.

## 7. API surface (v1)

All routes are prefixed `/api/v1` and require a bearer token except `POST /auth/login` and `/actuator/health`.

| Method | Path | Requirement |
|---|---|---|
| `POST` | `/auth/login` | FR-1.1 |
| `GET` | `/employees?page&size&sort&q&departmentId&countryCode&status&employmentType&jobLevel` | FR-2.2 – FR-2.4 |
| `POST` | `/employees` | FR-2.1 |
| `GET` | `/employees/{id}` | FR-2.5 |
| `PUT` | `/employees/{id}` | FR-2.1, FR-2.6 (`If-Match` / version) |
| `DELETE` | `/employees/{id}` | FR-2.1 (soft delete) |
| `GET` | `/employees/{id}/salaries` | FR-3.1 |
| `POST` | `/employees/{id}/salaries` | FR-3.2 – FR-3.6 |
| `GET` | `/analytics/summary` | FR-4.1 |
| `GET` | `/analytics/by-group?groupBy=DEPARTMENT\|COUNTRY\|JOB_LEVEL` | FR-4.2 |
| `GET` | `/analytics/distribution` | FR-4.3 |
| `GET` | `/analytics/pay-bands` | FR-4.4 |
| `GET` | `/analytics/gender-gap?groupBy=` | FR-4.5 |
| `GET` | `/analytics/trend?from&to&interval=` | FR-4.6 |
| `POST` | `/imports/employees` (multipart) | FR-5.1 – FR-5.3 |
| `GET` | `/imports/{id}` | FR-5.6 |
| `GET` | `/imports/{id}/errors.csv` | FR-5.4 |
| `GET` | `/imports/template.csv` | FR-5.4 |
| `GET` | `/exports/employees.csv?<same filters>` | FR-5.5 |
| `GET` | `/reference/{departments,locations,job-roles}` | supports UI filters |

Every analytics endpoint accepts the same filter parameters as `/employees`, satisfying FR-4.7.
Errors use RFC 7807 `application/problem+json` with a stable `code` field.

## 8. Architecture

```
Angular 21 SPA (Vercel) ──HTTPS/JWT──►  Spring Boot 3.5 API (Render)  ──JDBC──►  Postgres 18 (Neon)
  standalone components                    controller → service → repository
  Angular Material                         Flyway-managed schema
  typed HTTP client                        DTO boundary, no entity leakage
```

**Backend layering** — dependencies point one way only:
`controller` (HTTP, validation, DTO mapping) → `service` (business rules, transactions) →
`repository` (persistence) → `domain` (entities, value objects, pure logic).
Analytics uses a dedicated read-model package with hand-written SQL projections rather than JPA
entity graphs, because aggregation is a reporting concern and forcing it through an ORM is how these
systems get slow.

**Chosen stack and rationale**

| Choice | Why | Alternative rejected |
|---|---|---|
| Java 17 + Spring Boot 3.5.3, Gradle | Java 17 is what is installed; Gradle is present, Maven is not. 3.5.3 is the current stable release, verified against Maven Central (ADR-0002) | — |
| PostgreSQL 18 on Neon (free tier) | Real `NUMERIC`, window functions, `EXCLUDE` constraints, `percentile_cont` for medians, `JSONB` for audit. Free forever, no card. (Planned as 16; Neon provisioned 18 by default — verified working, ADR-0007. Test engine pinned to 17.5, the newest Zonky/Flyway currently support.) | SQLite: weak typing, no native decimal, limited analytics SQL. H2: not production-grade |
| Flyway | Versioned, reviewable schema; `ddl-auto` is banned (NFR-5) | Hibernate auto-DDL |
| Angular 21.2 standalone + Angular Material | Brief specifies Angular for Java. Material gives an accessible data grid without hand-rolling a table. 21.2 rather than 22 because the installed Node 22.18 does not meet Angular 22's engine requirement (ADR-0002) | — |
| JUnit 5 + AssertJ + Mockito | Fast unit tests with readable assertions | — |
| Zonky embedded-postgres for repository tests | Real Postgres without Docker (unavailable on this machine), so `EXCLUDE` constraints and `percentile_cont` are genuinely exercised | Testcontainers needs Docker; H2 would not execute the SQL we actually ship |
| Render (API) + Vercel (SPA) + Neon (DB) | All free, no card. Render builds remotely, so no local Docker needed | Railway is trial-credit, so the demo link expires |

**Known limitation to state rather than hide:** Render's free tier sleeps after ~15 minutes idle, so
the first request after a pause takes ~30 s. This is documented in the README with the trade-off
(free-forever hosting vs. always-on) rather than papered over.

## 9. Test strategy (TDD)

Development is test-first: **RED → GREEN → REFACTOR**, with the failing test committed as evidence of
the cycle. The pyramid:

| Layer | Tooling | What it covers | Speed |
|---|---|---|---|
| **Unit** (the bulk) | JUnit 5 + AssertJ, no Spring context | Salary interval transitions, currency normalisation, annualisation, compa-ratio, percentile maths, CSV row validation, JWT rules | milliseconds |
| **Repository** | Zonky embedded Postgres + Flyway | Real SQL: analytics projections, the `EXCLUDE` overlap constraint, search and pagination | seconds, one shared instance |
| **API** | `@SpringBootTest` + MockMvc | Auth enforcement, status codes, validation errors, optimistic-lock conflict → `409` | seconds |
| **Frontend** | Jasmine + Karma, real Chrome | Component rendering, filter state, interceptor attaches token, guard redirects | seconds |

Karma is a deliberate choice, not the Angular 21 default: the framework now scaffolds a Vitest/jsdom
runner instead. We test against a **real browser** rather than a DOM simulation, so component tests
exercise genuine layout, CSS and focus behaviour. The cost — a browser must be installed locally and
in CI — is accepted. See `docs/adr/0004`.

**Determinism rules (NFR-3):** time is injected via `java.time.Clock` and fixed in tests — production
code never calls `LocalDate.now()` directly; random data uses a fixed seed; no test touches the
network; tests share no mutable state and pass in any order.

## 10. Milestones

Each milestone ends with: green tests → analyst review → quality review → human approval → commit.
The workflow is defined in `docs/WORKFLOW.md` and enforced by `CLAUDE.md`.

| # | Milestone | Delivers | Exit criteria |
|---|---|---|---|
| **M0** | Foundation | Repo, `.gitignore`, Gradle + Spring Boot skeleton, Angular skeleton, CI, this spec, `CLAUDE.md` | `./gradlew build` and `ng build` both pass |
| **M1** | Data model | Flyway migrations for all 10 entities, JPA entities, repositories | Schema applies cleanly; repository tests green |
| **M2** | Auth | `app_user`, JWT issue/validate, Spring Security, login endpoint | Protected route returns `401` without a token |
| **M3** | Employee CRUD | FR-2 end to end, optimistic locking | Concurrent-update test yields `409` |
| **M4** | Compensation | FR-3: temporal records, FX normalisation, audit log | Overlap rejected at both service and DB level |
| **M5** | Seed | FR-6: 10,000 employees, deterministic | Repeatable run; timing recorded |
| **M6** | Analytics | FR-4 endpoints on the read model | Statistics verified against fixtures; NFR-1 measured |
| **M7** | Import/export | FR-5 with row-level errors | Malformed file writes nothing; errors listed per row |
| **M8** | UI | Login, directory, detail, salary form, dashboard, import | Works against the seeded 10k dataset |
| **M9** | Deploy | Neon + Render + Vercel live, demo video, artifacts | Public URL responds; video recorded |

Commits are small and scoped to one requirement ID, so the history reads as the development process
it actually was.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Analytics queries slow at 10k | Measure at M6 against the real seeded dataset; indexes and the current-salary view designed up front |
| Render cold start damages the demo | Document it; warm the instance before recording the video |
| ~~`btree_gist` unavailable on Neon~~ **Resolved** | Verified on the embedded test engine at M1, and against the real Neon instance during M1 (ahead of the original M9 schedule) — all five migrations applied cleanly, `btree_gist` and `pg_trgm` both installed under the app's role. See ADR-0007. Fallback (service-level enforcement plus a unique partial index on `(employee_id) WHERE effective_to IS NULL`) is no longer needed and kept only as a documented alternative. |
| Embedded Postgres slows the suite | Unit tests carry the bulk and need no database; the embedded instance starts once per run |
| Scope creep from AI-generated extras | `CLAUDE.md` forbids unrequested features; the analyst review gate checks every change against a requirement ID |
