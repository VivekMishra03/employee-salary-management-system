# ADR-0014 — Seed data (M5): exact money, realistic shape, guarded wipe

- **Status:** Accepted — items marked ⚠ are judgment calls the human should confirm or overrule
- **Date:** 2026-09-24
- **Implements:** FR-6.1, FR-6.2, NFR-2, NFR-4, NFR-7

## Context
The first seed (a Node script, `scripts/seed.js`) loaded 10,000 employees into Neon and worked. The
spec-compliance review then found that it did not meet several requirements it claimed to meet. This ADR
records what changed and what is still open. Nothing here edits requirements.md.

## Decisions

### Money is exact (NFR-2)
requirements.md bans floating point for money "anywhere in the stack". The seed did raises and currency
conversion in JS floats, and a replication showed about 0.17% of conversions off by one cent against the
backend's `SalaryCalculator` (`BigDecimal`, full 8-decimal rate, `HALF_UP` once). The seed now uses BigInt
cents and rates scaled by 10^8, with the same rounding as the backend. The tests use golden vectors copied
from `SalaryCalculatorTest` and a randomised cross-check against an independent oracle, so the two
implementations cannot drift apart silently. Dataset rows keep money as strings such as `1234.50`.

### Determinism does not depend on the machine (FR-6.2)
The seed parsed dates as UTC but stepped and formatted them with local-time getters, so the same seed could
give different dates in different time zones. It now uses UTC throughout, and a test generates the dataset
under three zones and requires identical output. One exception is documented: the HR account's password hash
differs between runs because BCrypt salts randomly.

### Realistic shape (FR-6.1) ⚠
"Realistic pay distributions" is not defined in the spec. Chosen, so country and pay-band analytics show
something:
- **Location pay differential** in USD terms: New York 1.00, San Francisco 1.10, London 0.85, Berlin 0.80,
  Singapore 0.75, Bengaluru 0.35. Pay bands use the same scheme.
- **Volume:** about 3.5 salary records per employee, so about 35,000 for 10,000 employees (NFR-1's
  figure). The first seed produced about 30,200.
- **Pay-band spread:** about 12% below, 76% within, 12% above band, so the FR-4.4 report has people to
  name. Tests keep these shares inside loose ranges.
- **Pay frequency variety:** about 30% of contractors are hourly and about 10% of the others monthly,
  so annualisation (x2080, x12) is exercised by real data and not only by unit tests.
- **No gender pay gap is built in.** Gender is independent of pay, so the FR-4.5 panel will show gaps near
  zero, with some random noise in small groups. A deliberate gap would make a demo more striking but would be
  synthetic bias presented as data. Not added; say if you want one.

### Parameterised inserts (NFR-4)
Database inserts now use parameterised multi-row statements with fixed table and column names. The
`--dump-sql` file keeps escaped literals, because it is a script for a person to review, and says so in its
header. Error output prints only message, code and constraint, since a constraint error's detail can quote a
whole row.

### Guarded wipe (NFR-7) ⚠
`--clean` runs `TRUNCATE ... CASCADE`, which empties far more than seeded data: the audit log and import
tables too. It now also needs `--confirm-wipe`, refuses before contacting the database without it, and names
every table it would empty. The spec asks only that the seed be repeatable; the wipe exists so a re-seed is
possible at all and is not required by FR-6.

### The HR password
No default and no value in the repository: it comes from `SEED_HR_PASSWORD` (12 to 72 bytes), and
`--reset-hr-password` changes it on an already-seeded database. The earlier password and hash remain in git
history and were live on the seeded account until it is reset.

## Still open
- **M5 timing** is recorded in docs/perf/seed-run-2026-09-24.md: 29.22 s as reported by the script, with the row
  counts queried afterwards. It is one run.
- **The Neon database now holds this version of the dataset** (loaded 2026-09-24 with a wipe, so the audit log
  and anything created through the UI before then are gone). The employee count is 10,000; the earlier 10,001
  was a UI-created record.
- **The HR password** must be changed with `--reset-hr-password` if it has not been already: the previous
  password was public in the repository.
- Parameterised multi-row inserts were tested against fake clients and have now run once against PostgreSQL
  (the run above succeeded); a second run against an empty database is not covered.
- NFR-1's server-side timings were first taken on the earlier dataset (30,233 salary records) and were
  repeated on this one: docs/perf/nfr-1-analytics-2026-09-24-reseeded.md (all well inside the limits; the
  request latency on the deployed service is still not measured).

## Addendum from the second spec review
- **Exchange-rate provenance.** The seeded rates are hand-picked round numbers, not published rates. Their
  `source` column now reads `SEED_SYNTHETIC` instead of `ECB_HISTORICAL`, which claimed a provenance the data
  does not have. Rows already in Neon still carry the old label until the next seed.
- **Extras beyond FR-6:** `--dump-sql`, `--help` and `--reset-hr-password` are not required by the spec. They
  are small and tested; `--reset-hr-password` follows from NFR-4 (the committed password), the other two are
  conveniences.
- **Bands and FX disagree slightly.** The per-currency constants used to state bands in local currency
  (for example 0.78 GBP per USD) are separate from the dated `exchange_rate` rows (1.29 in 2026), so a band
  converted at the stored rate differs by a few percent from its USD design value. Compa-ratios use local
  currency, so analytics are unaffected.
- **Review-scope gap for the human.** CLAUDE.md section 3 triggers the code-correctness review only for
  `backend/src` and `frontend/src`. `scripts/seed.js` (about 1,200 lines of money and destructive-SQL logic)
  falls outside it, so that gate does not run on M5 by the letter of the rule. Whether to extend the rule to
  `scripts/**` is the human's decision; CLAUDE.md has not been edited.
- **Determinism** is asserted at 300 employees in committed tests, and checked at 10,000 only by hand in
  separate processes.
