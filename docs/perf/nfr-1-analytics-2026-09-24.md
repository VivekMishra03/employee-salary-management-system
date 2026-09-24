# NFR-1 measurement — employee list and analytics (M6)

- **Date:** 2026-09-24
- **Requirement:** NFR-1 — employee list p95 < 500 ms, analytics p95 < 1 s, at 10,000 employees and about
  35,000 salary records, "measured against the seeded dataset rather than asserted".
- **Dataset:** the seeded Neon database, PostgreSQL 18.6: 10,001 employee rows, 30,233 salary_record rows,
  288 pay_band rows. (The extra employee row was not investigated.)
- **Tools:** `scripts/explain-analytics.mjs` (server-side, `EXPLAIN (ANALYZE, BUFFERS)`) and
  `scripts/measure-api.mjs` (end to end over HTTP).

## What was measured, and what it can and cannot show

| Method | Shows | Does not show |
|---|---|---|
| `EXPLAIN ANALYZE` reports **Execution Time** inside the database | The cost of the SQL itself, with no network in it | Spring, JDBC, JSON, or network latency |
| End-to-end over HTTP from the developer's laptop | That every endpoint works against real data | A usable latency. Neon is in Ohio and the laptop is in India, so one trivial query already costs about 555 ms. The list makes three database round trips, so it reads about 1.7 s. That is the network, not the code. |

The Render service and Neon share a region, so a request made there does not pay that price. **The
deployed-service p95 is still to be measured** once this build is deployed, and NFR-1 is not signed off until
it is. The figures below are the server-side part only.

## Server-side execution time (15 runs after 2 warm-ups; with 15 samples p95 is the maximum)

Unfiltered slices, `asOf` 2025-12-31.

| Query | p50 ms | p95 ms |
|---|---:|---:|
| employee list, page 1 (25 rows) | 2.0 | 2.2 |
| employee list, page 301 | 8.1 | 9.0 |
| employee count | 1.4 | 1.4 |
| employee search `smith` (page) | 4.5 | 5.0 |
| analytics summary | 16.6 | 17.9 |
| by-group DEPARTMENT / COUNTRY / JOB_LEVEL | 21.1 / 18.7 / 17.2 | 25.6 / 23.9 / 20.2 |
| distribution, 12 buckets | 36.8 | 42.9 |
| distribution, 50 buckets | 100.6 | 108.9 |
| pay-bands counts / page | 22.6 / 24.8 | 28.8 / 33.3 |
| gender-gap DEPARTMENT / JOB_LEVEL | 21.0 / 18.9 | 24.7 / 27.5 |
| **trend, 120 monthly periods (before)** | 875.2 | **921.3** |
| **trend, 120 monthly periods (after)** | 194.6 | **206.8** |
| trend, 60 monthly periods (before → after) | 643.4 → 192.1 | 698.6 → 346.9 (one outlier) |
| trend, 40 quarterly periods (before → after) | 381.9 → 196.1 | 403.2 → 206.1 |

## What changed because of the measurement
The first trend query joined every period to every salary row, so its cost grew with the number of periods
and the 120-period worst case (921 ms) left no room under the 1 s limit once API overhead was added. It was
rewritten to place each salary change in its period once and take a running sum across periods (a difference
array). The old query is kept as a test-only reference (`TrendAnalyticsReferenceOracleTest`), which compares
old and new on a fixed-seed fixture, and the two were also compared on the real seeded data across nine
windows and all three intervals. Results were identical.

## Caveats
- Filtered slices (department, country, search) were not measured, only unfiltered ones.
- Runs were sequential and warm on a single connection. This is not a load test. Neon's free tier can be
  slower after it suspends.
- The employee-list SQL in the script is hand-written to mirror Hibernate's, so it approximates it.
- The SQL in the script is copied from the Java sources and can drift. The script header lists the
  file and line references to re-check.
- The `q` search does a full scan: the trigram index is on the un-lowered text and the query lowercases
  it. At 10,000 rows this costs about 5 ms, so it is left alone (ADR-0010 anticipated this).
- Distribution cost grows with the bucket count (109 ms at 50). Acceptable at the 50-bucket cap.
