# ADR-0011 — Salary recording: contiguous timeline, historical FX, atomic audit

- **Status:** Accepted — items marked ⚠ are consequences the human should know about
- **Date:** 2026-09-21
- **Implements:** FR-3.1 – FR-3.7

## The timeline model
An employee's salary history is a sequence of half-open intervals `[from, to)` that **tiles the period
from the hire date onward** — no gap, no overlap, exactly one open-ended (`to = NULL`) record at the end.
Recording a change is decided by a pure function, `SalaryTimeline.plan`, with no database involved:

- the record **in force on the effective date** is cut to end there;
- the new record runs from the effective date to **wherever that record used to end**. So a raise on the
  latest record stays open-ended, and a **back-dated change slots into the middle of history** ending where
  the next record begins.

Rejections (all 400, stable `code`, field `effectiveFrom`): `EFFECTIVE_BEFORE_HIRE`,
`EFFECTIVE_AFTER_TERMINATION`, `GAP_AFTER_HIRE` (a first record after the hire date would leave a gap),
`TIMELINE_GAP` (existing history stops short of the date).

**Same-day change = supersede.** A change effective on the day an existing record starts cuts that record
to **zero length** rather than deleting it (requirements.md assumption 4: corrections supersede, history is
never erased). The database accepts the empty range under the EXCLUDE constraint (proved by test). ⚠ The
history endpoint therefore includes these zero-length rows (`effectiveFrom == effectiveTo`); the UI should
hide or mark them.

## Two enforcement points, as section 6.3 requires
1. **Service** — `SalaryTimeline` above.
2. **Database** — the `EXCLUDE USING gist` constraint, the backstop for any path that bypasses the service.

The service must **shorten the old record and flush before inserting the new one.** Hibernate runs a
flush's INSERTs before its UPDATEs, and the new record overlaps the old one until it is shortened, so
without the explicit `saveAndFlush` the constraint rejects the insert. Verified by removing it: ten tests
fail.

## Money and FX
- `SalaryCalculator` is pure: annual ×1, monthly ×12, hourly ×2080; conversion multiplies by the rate at
  its full 8-decimal precision and rounds **once**, `HALF_UP`, to 2 places (a test pins the exact-half
  case that `HALF_EVEN` gets wrong).
- **The currency is the employee's location currency, never a request field** (FR-3.4). ⚠ A salary cannot
  be booked in a currency other than the employee's location's. If an employee relocates, old records keep
  their currency and new ones use the new location's.
- **FX uses the rate effective on the record's `effective_from`** — the latest rate on or before that date —
  so a back-dated record converts at the rate of its time (FR-3.5). Only a direct `currency → base` pair is
  used; an inverse is never derived. A missing rate, or a non-positive one, is `409
  EXCHANGE_RATE_UNAVAILABLE`, never a guess. The base currency is configurable (`BASE_CURRENCY`, default USD).
- An annualised or converted amount that would overflow `NUMERIC(15,2)` is a 400 `AMOUNT_OUT_OF_RANGE`,
  not a database error.

## Audit (FR-3.7) and atomicity (FR-3.2)
One raise writes: **UPDATE** audit for the closed record (before/after state) and **CREATE** audit for the
new one, attributed to the authenticated user (from the token, never the request).

`AuditLogService.record` uses `Propagation.MANDATORY`: it refuses to run outside a transaction, so an audit
entry commits or rolls back **with** the change it describes. `SalaryAtomicityTest` proves it by forcing
the *last* write (the CREATE audit) to fail after the old record was already truncated and flushed, and
asserting the truncation, the new record and the earlier audit entry are all undone.

## Consequences accepted
- ⚠ **No row locking.** Two simultaneous raises for the *same* employee could each plan against stale
  history; the EXCLUDE constraint prevents corruption, and the loser gets a 409
  `DATA_INTEGRITY_VIOLATION` and retries. Acceptable for a single HR user; add a `PESSIMISTIC_WRITE` on the
  employee row if that changes.
- **Termination and salary (confirmed).** Deactivating an employee does not close their open salary record,
  so a terminated employee still has an open-ended record. Analytics (M6) must filter by employment status
  rather than assume "open-ended record ⇒ currently paid". Salary changes may still be recorded up to the
  termination date.
- Salary records are never edited or deleted; a wrong amount is corrected by a superseding record.

## Test-infrastructure lessons
- A test annotated `NOT_SUPPORTED` runs its class's `@BeforeEach` **outside any transaction** (Spring starts
  the test transaction from the *method's* attribute), so its seed data auto-commits and leaks into every
  later test — this took down 25 tests. Such a test lives in its own class with no seeding
  (`AuditLogServiceTest`).
- A raw `JdbcTemplate` read cannot see inserts still pending in Hibernate's session; flush first.
- Stubbing a `@MockitoSpyBean` that sits behind a transactional proxy must target the spy underneath
  (`AopTestUtils`), or the stubbing call itself runs the proxy's interceptor.
