# ADR-0010 — Employee directory: update semantics, search, filters, paging

- **Status:** Accepted — **items marked ⚠ are judgment calls the spec does not settle and need the human's confirmation**
- **Date:** 2026-09-21
- **Implements:** FR-2.1 – FR-2.6

## Decisions

### Update semantics ⚠
`PUT /employees/{id}` is a full replacement of the *editable* fields. Three things are deliberately
**not** editable through it, because the spec does not say they should be and each has a knock-on effect:

| Not editable | Why |
|---|---|
| `employeeCode` | It is the natural key HR uses in CSVs (FR-5); changing it silently breaks re-imports |
| `hireDate` | It bounds every salary interval (section 6.2). Editing it once salary records exist could leave a gap or overlap; that interaction belongs with the salary service (M4) |
| Status → `TERMINATED` | Only soft delete does that, because it must also set `termination_date` (DB CHECK) |

A **terminated employee cannot be edited or reactivated** (409 `EMPLOYEE_TERMINATED`). Reinstatement is
not in the spec. If HR needs to correct a typo on a terminated record, or rehire, that is a new
requirement, not something to guess at.

### Optimistic locking (FR-2.6)
The request body carries `version` (required — its absence is a 400, so a blind overwrite is impossible).
Two layers, on purpose:
1. An **explicit check** in the service gives a clear 409 `CONCURRENT_UPDATE`.
2. `@Version` on the entity catches the residual race where two requests both pass the check, surfacing
   as `OptimisticLockingFailureException`, mapped to the *same* 409 so a client handles one case.

The spec's table says "(`If-Match` / version)"; the body field was chosen over the header because it
needs no ETag plumbing and is the same for the frontend either way.

### Soft delete (FR-2.1)
`DELETE` sets `TERMINATED` and `termination_date` = today from the injected `Clock`, **or the hire date
if the hire has not started yet** (`termination_date >= hire_date` is a DB CHECK, so "today" would
violate it). Idempotent: a second delete changes nothing and keeps the original date. ⚠ **Open for M4:**
deactivating does *not* close an open salary record, so a terminated employee still has a "current"
salary. Whether termination should end the salary interval (and write an audit entry, FR-3.7) is a
salary-service decision.

### Search (FR-2.3)
Case-insensitive substring match over `first name, last name, code, email`. The query is split on
whitespace and **every word must match, in any order** ("byron ada" finds Ada Byron). `%`, `_` and `\`
are escaped so they are literal. ⚠ **Not yet verified: whether this uses the `pg_trgm` GIN index from
V5.** Matching that index requires the query expression to be textually identical to the indexed one
and case-insensitive without wrapping it in `lower()`; the current implementation (correct, and
parameterised) does not attempt that. At 10,000 rows a sequential scan is milliseconds, so this is
correct-first. **Measure with `EXPLAIN` against the seeded dataset at M5/M6 (NFR-1) and adapt only if
the numbers demand it** — an index that is built but unused is otherwise dead weight.

### Filters (FR-2.4) and reuse (FR-4.7)
`EmployeeFilter` is a standalone record, not loose controller parameters, because analytics (FR-4.7) and
CSV export (FR-5.5) must accept the *same* filters. The JPA `Specification` built from it lives in
`service/`, not `repository/`: it depends on an API-level type, and NFR-5 makes layering one-way.
⚠ Analytics uses hand-written SQL (CLAUDE.md §7), so M6 will need its own translation of this filter —
the type is shared, the SQL is not.

### Paging (FR-2.2)
- Page size capped at 100 (`spring.data.web.pageable.max-page-size`), default 20.
- **Sortable fields are a whitelist** (`employeeCode, firstName, lastName, email, hireDate,
  employmentStatus, employmentType`); anything else is a 400 `INVALID_SORT`. `gender` is excluded on
  purpose (assumption 3).
- Every sort gets a trailing `id` tiebreaker. Without it, ordering by a non-unique column lets the
  database return tied rows in different orders per request, so a row can appear on two pages or none.
  Tested as a pure unit (`EmployeeSortTest`): a paged-query test *cannot* pin this, because a small table
  returns ties consistently by luck — removing the tiebreaker left that test green.
- The list response is our own `PageResponse`, not a serialised Spring `Page` (whose JSON is not a
  stable contract).

### Entities cross no boundary; no N+1
Foreign keys are plain `Long` columns (M1), so list rows are assembled with **three batched lookups per
page**, not three per row. The list omits `gender` (assumption 3); the detail view includes it.

## Consequences
- Every error is RFC 7807 with a stable `code`; validation and conflict responses never echo request values.
- Salary data in the detail view (current compensation, history) is read-only here; writing it is M4.
