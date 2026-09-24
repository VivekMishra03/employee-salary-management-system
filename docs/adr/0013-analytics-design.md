# ADR-0013 — Analytics (M6): definitions the spec leaves open

- **Status:** Implemented in M6, awaiting confirmation of the items marked ⚠ (judgment calls the human should confirm or overrule)
- **Date:** 2026-09-23
- **Implements:** FR-4.1 – FR-4.7, NFR-1, NFR-2

requirements.md fixes what FR-4 must answer but not several definitions. Each is decided here so the
code, the tests and the UI agree, and so a different reading can be substituted in one place.

## Where the code lives
A new package `com.acme.salary.readmodel` holds hand-written SQL and its row records (CLAUDE.md
section 7; requirements.md section 8 names "a dedicated read-model package"). ADR-0008 fixed the
package-by-layer set without addressing analytics, so this extends it rather than contradicting it.
Layering stays one-way: `controller → service → readmodel`. Services own rules (minimum group size,
suppression); the read model owns SQL. All SQL is parameterised; nothing is concatenated from input.
Money is `BigDecimal` end to end; SQL rounds with `ROUND(x, 2)` and Java never uses `double`.

## The population ⚠ (conflicts with the spec's `v_current_salary`)
requirements.md section 6.2 defines `v_current_salary` as "one row per employee (`effective_to IS
NULL`)". That view **does not exist** in the migrations, and its definition contradicts FR-3.6: with a
future-dated raise on file the open-ended record is the *future* one, so the employee's salary as of today
would be misreported. Decision: **no view.** One shared SQL fragment selects the record in force on an
`as_of` date, `effective_from <= :asOf AND (effective_to IS NULL OR effective_to > :asOf)`, where `asOf` is
today from the injected `Clock` (NFR-3; `CURRENT_DATE` in SQL would make tests date-dependent). Zero-length
superseded rows (ADR-0011) can never match because `from = to` fails `to > asOf`.

Terminated employees are **excluded unless the caller filters `status` explicitly** (ADR-0011: analytics
must not assume an open-ended record means "currently paid"). On leave counts as employed.

## Filters (FR-4.7)
Every endpoint takes the same parameters as `GET /employees`: `q, departmentId, countryCode, status,
employmentType, jobLevel`, bound to the existing `EmployeeFilter`. The SQL translation is a separate,
parameterised builder in `readmodel` (ADR-0010 predicted this), unit-tested for its generated text and
parameters.

## Money and part-timers ⚠
Stored `annualised_amount*` is treated as the **full-time-equivalent annual rate** (the seed and the pay
bands work this way; a band comparison is only valid on an FTE rate). Therefore:
- pay statistics, group medians, the histogram, compa-ratio and gender gap use the FTE rate **unscaled**;
- **total payroll cost** is `SUM(annualised_amount_base_ccy × fte_ratio)`, the money actually paid.
The spec says "analytics compare FTE-adjusted pay" without saying whether the stored figure is already
adjusted; if it is actual pay, statistics must divide by `fte_ratio` instead. One place to change.

## Definitions
| Item | Definition |
|---|---|
| Statistics (FR-4.1) | headcount, payroll cost, mean, and `percentile_cont` (linear interpolation) at 0.25 / 0.50 / 0.75 of the base-currency FTE rate; empty slice → zeros and nulls, never an error |
| Group (FR-4.2) | `groupBy = DEPARTMENT | COUNTRY | JOB_LEVEL`; per group: key, label, headcount, median, mean, ordered by label |
| Histogram (FR-4.3) | equal-width buckets between the slice min and max; `buckets` param default 12, 2–50; bucket = `[lower, upper)`, last bucket closed; every employee in exactly one bucket; a slice where min = max is a single bucket |
| Compa-ratio (FR-4.4) | `annualised_amount / mid_amount` in **local currency**, 2 decimals HALF_UP, for the pay band of the employee's role **and** location in force on `asOf` |
| Adherence (FR-4.4) | `BELOW` if amount < min, `ABOVE` if amount > max, otherwise `WITHIN` (bounds inclusive). No band → `NO_BAND`, counted separately, never guessed |
| Pay-band response | the four counts plus a **server-paginated** list of employees (names them), filterable by adherence, default sorted by compa-ratio ascending |
| Gender gap (FR-4.5) | `(mean_M − mean_F) / mean_M × 100` and the same for medians, 2 decimals HALF_UP; positive = women paid less. Only `MALE` and `FEMALE` are compared; other values and null are excluded from the gap |
| Suppression (FR-4.5) ⚠ | a group is suppressed (gap fields null, `suppressed: true`) unless **both** genders have at least `app.analytics.min-group-size` employees. The spec says "minimum size" without a number; default **5**, configurable |
| Trend (FR-4.6) | `interval = MONTH | QUARTER | YEAR` between `from` and `to` (max 120 periods). Per period: payroll = sum of `annualised_amount_base_ccy × fte_ratio` of records in force on the period's last day for employees employed then; average increase % = mean of `(new − previous) / previous` over salary changes effective in the period, excluding `NEW_HIRE` and `CORRECTION`, comparing local-currency annualised amounts and skipping a change whose currency differs from its predecessor's |

## Performance (NFR-1)
The analytics endpoints must be measured against the seeded 10,000-employee dataset, not asserted.
Measurement is recorded in `docs/perf/` with the query plan for any endpoint over 1 s. Indexes are added
by a new migration only if a measured plan demands it.

## Decisions made while building M6 (not in the original design)
- **Terminated employees and the trend.** The default exclusion of terminated employees applies to the
  point-in-time endpoints only. `/trend` measures payroll on each period's last day and counts anyone
  employed that day, so a person who left in 2024 is in 2023's payroll. A caller-supplied `status` filter
  still applies to the person's *current* status. Department, country, level and type filters also use
  *current* attributes: they are not historised, so `department = Sales` means people in Sales now, seen
  across the whole range.
- **Pay bands and currency.** A band applies only to a salary in the **same currency**. An employee moved to
  another country keeps a salary in the old currency until a new record is written, and comparing GBP
  60,000 to EUR band limits would report a currency difference as a pay finding. With no band in the
  salary's currency the employee is `NO_BAND`. No exchange rate is applied. Several bands can be in force
  at once (the schema only makes role, location and start date unique); the most recently started one in
  the salary's currency is used, so nobody is listed twice.
- **Pay-band endpoint parameters.** `adherence`, `page` and `size` (capped at 100) are added to the
  section 7 row for `/analytics/pay-bands`, and a fourth class `NO_BAND` exists so a missing band is never
  read as `WITHIN`.
- **Suppressed gender-gap groups** return null counts as well as null gaps: exact counts of a small group
  help re-identify people. Threshold suppression still allows two overlapping slices to be differenced; that
  residual risk is inherent and accepted.
- **Trend limits.** At most 120 periods, and `from` and `to` must fall in years 1900 to 2200
  (`DATE_OUT_OF_RANGE`). The year bound closes a bypass found in review: a span of hundreds of millions of
  years overflowed a 32-bit period count, passed the 120 check, and became an unbounded query.
- **Trend method.** Payroll is a difference array with a running sum, and increases are grouped by calendar
  bucket, because the first version joined every period to every salary row and took 921 ms for 120
  periods (docs/perf/nfr-1-analytics-2026-09-24.md). The first version is kept as a test-only oracle.
- **Histogram edges** are rounded once and membership is decided against those same rounded edges, so a
  value on a displayed edge is in the bucket that shows it.
- **`percentile_cont` works in double precision** inside PostgreSQL; medians are cast to NUMERIC and
  rounded to 2 decimals in SQL, so no double reaches Java. A half-cent interpolation edge could differ from
  exact decimal arithmetic; not tested.

## Still open
- **NFR-1 on the deployed service** is not yet measured. Server-side times are recorded in
  docs/perf/nfr-1-analytics-2026-09-24.md; the request-level p95 needs a run against the deployed API,
  which shares a region with the database.
- requirements.md section 6.2 still describes `v_current_salary` and a partial index on
  `effective_to IS NULL` as the current-salary lookup. Both assume an open-ended record is the current
  one, which FR-3.6 contradicts. The spec text needs a human decision; it has not been edited.

## Consequences
- The spec's `v_current_salary` stays unbuilt; this ADR is the record of why. requirements.md is not edited.
- Repository tests run the real SQL on embedded PostgreSQL, including `percentile_cont`.

## Addendum 2026-09-24: exchange-rate "as of" date
requirements.md assumption 2 says stale exchange rates are "surfaced with an 'as of' date in the UI rather
than hidden". `GET /analytics/summary` therefore returns `ratesAsOf`: the latest `effective_date` in
`exchange_rate` (null when the table is empty). It is one parameterless `MAX` query, independent of the
filters and of the clock, because it describes the rate table and not the slice. The dashboard shows it
beside the note that each salary is converted at the rate in force when it was recorded (FR-3.5).
