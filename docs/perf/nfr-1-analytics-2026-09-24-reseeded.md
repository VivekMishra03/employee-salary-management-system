# NFR-1 server-side timings on the re-seeded dataset

- **Date:** 2026-09-24, after the seed run in `seed-run-2026-09-24.md`.
- **Dataset:** 10,000 employees, 34,917 salary records, 288 pay bands (row counts queried, not assumed).
- **Method:** `node scripts/explain-analytics.mjs`, the same script and method as
  `nfr-1-analytics-2026-09-24.md`: `EXPLAIN (ANALYZE, BUFFERS)` inside a read-only transaction, 15 runs after
  2 warm-ups, database execution time only. With 15 samples the "p95" is the worst of the 15.
  The trend SQL in the script is the rewritten version described in the earlier record.

| Query | p50 ms | p95 ms |
|---|---:|---:|
| employee list, page 1 (25 rows) | 2.1 | 2.4 |
| employee list, page 301 | 8.0 | 8.7 |
| employee count | 1.4 | 2.6 |
| employee search `smith`, count / page | 4.4 / 4.5 | 5.9 / 9.4 |
| analytics summary | 16.3 | 21.2 |
| by-group DEPARTMENT / COUNTRY / JOB_LEVEL | 20.4 / 18.5 / 17.7 | 26.5 / 22.9 / 20.6 |
| distribution, 12 buckets | 37.5 | 42.0 |
| distribution, 50 buckets | 101.1 | 108.5 |
| pay-band counts / page | 21.9 / 24.8 | 23.0 / 34.5 |
| gender-gap DEPARTMENT / JOB_LEVEL | 20.5 / 18.2 | 21.7 / 24.5 |
| trend, 60 monthly periods | 186.3 | 209.9 |
| trend, 120 monthly periods | 188.6 | 194.7 |
| trend, 40 quarterly periods | 188.0 | 197.7 |

## Reading it
- The dataset is about 15% larger than the first one (34,917 records against 30,233), and the timings are
  essentially unchanged, apart from the trend query, which is now flat at about 190 ms whatever the number of
  periods. Before its rewrite it took 921 ms for 120 periods.
- Every query is far under its NFR-1 limit at the database. That is the database's share only.

## Still not measured
- Request latency of the deployed API. The Render service does not yet have the analytics endpoints, and the
  request time also includes Spring, JDBC, JSON and the network. NFR-1 is not signed off until that run is made.
- Filtered slices, and concurrent load.
