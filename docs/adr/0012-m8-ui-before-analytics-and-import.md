# ADR-0012 — M8 UI built ahead of M6 (analytics) and M7 (import/export)

- **Status:** Accepted — recorded at the human's direction; **the deferral of FR-4 and FR-5 is a scope
  change the human owns and should confirm before M9**
- **Date:** 2026-09-23
- **Affects:** requirements.md section 10 (M6, M7, M8 exit criteria); FR-4.1-4.7, FR-5.1-5.6

## Context

The milestone table orders M6 (analytics) and M7 (import/export) before M8 (UI). The human chose to skip
both for now and start M8. FR-4 and FR-5 are in section 2 scope (they are not in the section 4
out-of-scope table), so this is a **deferral, not a descoping**: the requirements still stand and are
still unmet.

Only these API routes exist today: `/auth/login`, `/employees` (list, create, get, update, delete),
`/employees/{id}/salaries` (list, record) and `/reference/*`. There is no `/analytics/*`, `/imports/*` or
`/exports/*`.

## Decision

M8 is delivered in two slices.

| Slice | Contents | Status |
|---|---|---|
| **M8a** | Login, guard and interceptor (FR-1), directory with search, filters and paging (FR-2.2-2.4), detail (FR-2.5), create, edit and deactivate with optimistic-lock handling (FR-2.1, FR-2.6), salary history and recording (FR-3) | Built |
| **M8b** | Analytics dashboard (FR-4). The import/export screens (FR-5) were dropped with M7, see the update below | **Built 2026-09-24** against the M6 API (ADR-0013) |

Consequences for the UI:

- The navigation and route table contain **only** screens the API can serve: Employees and, since M6,
  Analytics. There is no Import/Export link, so the UI never promises what the backend cannot deliver. A
  test (`shell.component.spec.ts`) pins the navigation, and `app.routes.spec.ts` proves every screen sits
  behind the auth guard.
- The earlier draft's `analytics.service.ts` was deleted while no endpoint existed. It invented response
  shapes, which is exactly the "invented API" failure CLAUDE.md section 4 forbids. The current service and
  models were written against the real DTOs and checked field by field against them.
- **Exchange-rate "as of" date (requirements.md assumption 2).** The summary response carries `ratesAsOf`,
  the latest effective date in the exchange-rate table, and the dashboard shows it. It describes the rate
  table, not the slice, so it does not change with filters.
- **Charts are hand-built inline SVG** (`shared/charts`), so no chart library was added. Each chart has a
  visually hidden data table as its text alternative.

## M8 exit criterion, restated honestly

requirements.md gives "Works against the seeded 10k dataset". As of this ADR **M8a is verified only with
component and service tests against a mocked HTTP layer**, plus a production build. It has **not** been
run against the real API or the 10,000-employee dataset. That observation is still owed, and M8 is not
complete until it is made and M8b is built.

## Other decisions made during M8a that the spec does not settle

- **Superseded salary rows are shown and marked**, not hidden (ADR-0011 allowed either). Hiding them would
  make the history disagree with the audit log, and requirements.md assumption 4 says the history is the
  audit trail.
- **Manager and direct reports are links**, so the RouteReuseStrategy in `core/routing` rebuilds the detail
  page when only the `:id` changes. FR-2.5 asks only that they be shown; the links and the strategy are
  an addition the human may remove.
- **A typed manager must be picked from the list.** Free text that matches no employee blocks saving rather
  than silently clearing the manager.
- **Dev proxy.** `ng serve` proxies `/api` to `http://localhost:8080`, so local development needs no CORS.
  CORS for the deployed origin is still deferred to M9 (ADR-0009).
- **JWT in `localStorage`.** Accepted for a single-user internal tool; the XSS trade-off is documented in
  `auth.service.ts` (CLAUDE.md section 7).
- **Frontend tests run in two non-UTC time zones in CI**, because a UTC runner cannot catch a
  `toISOString()` date bug. Unverified on the runner until the first CI run.

## Update 2026-09-24: M7 (import/export) removed from the current scope

The human decided to drop M7 and focus on finishing the rest. Consequences, stated so nothing implies
otherwise:

- **FR-5.1 – FR-5.6 (CSV import, row validation, error report, template, filtered export, import jobs) are
  not implemented and are not planned.** They are still in requirements.md section 2, which is
  deliberately not edited. The requirements therefore remain **unmet**, and anything reporting completion
  must say so.
- The M8b import/export screen is dropped with it. M8b now means the analytics dashboard only.
- No `/imports/*` or `/exports/*` route exists, and the UI has no Import/Export link. The tables
  `import_job` and `import_error`, and their entities, already exist from M1; they are unused and stay,
  since migrations are immutable.
- The spec-compliance review will keep reporting FR-5 as missing. That is correct, and is the reason the
  human may want a follow-up decision (amend section 2 with a recorded ADR, or reinstate M7 later).
- **The "Excel offramp" argument is weakened.** requirements.md section 2 says HR "must never feel
  trapped in the tool" (FR-5.5). With no export, that promise is not kept.

## Spec issue for the human

FR-3.3 lists six change reasons; section 6.2 and the backend enum have seven (`DEMOTION` is the extra).
The UI follows the backend. Needs a decision, not a silent edit of either side.
