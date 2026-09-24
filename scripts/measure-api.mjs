// NFR-1 measurement: latency of the employee list and every analytics endpoint against a running API
// that holds the seeded 10,000-employee dataset. Prints a markdown table (p50 / p95 / max) so the result
// can be pasted into docs/perf/. Nothing here is asserted: it only measures and reports.
//
//   PERF_BASE_URL=http://localhost:8080 PERF_EMAIL=... PERF_PASSWORD=... node scripts/measure-api.mjs [iterations]
//
// The credentials come from the environment, never from this file. Timing includes the client-to-API
// hop and, when the API talks to a remote database, that network too: the "baseline" row (one trivial
// authenticated query) shows the floor so the endpoint rows can be read against it.

const base = process.env.PERF_BASE_URL ?? 'http://localhost:8080';
const iterations = Number(process.argv[2] ?? 30);
const warmup = 3;

const email = process.env.PERF_EMAIL;
const password = process.env.PERF_PASSWORD;
if (!email || !password) {
    console.error('Set PERF_EMAIL and PERF_PASSWORD.');
    process.exit(1);
}

const login = await fetch(`${base}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
});
if (!login.ok) {
    console.error(`Login failed: HTTP ${login.status}`);
    process.exit(1);
}
const token = (await login.json()).accessToken;

const filtered = 'departmentId=2&countryCode=US&status=ACTIVE';
const endpoints = [
    ['baseline: reference/departments (1 trivial query)', '/api/v1/reference/departments'],
    ['employees list, first page (size 25)', '/api/v1/employees?page=0&size=25&sort=lastName,asc'],
    ['employees list, search q=smith', '/api/v1/employees?page=0&size=25&sort=lastName,asc&q=smith'],
    ['employees list, deep page 300', '/api/v1/employees?page=300&size=25&sort=lastName,asc'],
    ['analytics/summary', '/api/v1/analytics/summary'],
    ['analytics/summary (filtered)', `/api/v1/analytics/summary?${filtered}`],
    ['analytics/by-group DEPARTMENT', '/api/v1/analytics/by-group?groupBy=DEPARTMENT'],
    ['analytics/by-group COUNTRY', '/api/v1/analytics/by-group?groupBy=COUNTRY'],
    ['analytics/by-group JOB_LEVEL', '/api/v1/analytics/by-group?groupBy=JOB_LEVEL'],
    ['analytics/distribution (12 buckets)', '/api/v1/analytics/distribution?buckets=12'],
    ['analytics/distribution (50 buckets)', '/api/v1/analytics/distribution?buckets=50'],
    ['analytics/pay-bands (page 0)', '/api/v1/analytics/pay-bands?page=0&size=20'],
    ['analytics/pay-bands (BELOW only)', '/api/v1/analytics/pay-bands?adherence=BELOW&page=0&size=20'],
    ['analytics/gender-gap DEPARTMENT', '/api/v1/analytics/gender-gap?groupBy=DEPARTMENT'],
    ['analytics/gender-gap JOB_LEVEL', '/api/v1/analytics/gender-gap?groupBy=JOB_LEVEL'],
    ['analytics/trend 5y monthly (60 periods)', '/api/v1/analytics/trend?from=2021-01-01&to=2025-12-31&interval=MONTH'],
    ['analytics/trend 10y monthly (120 periods, worst case)', '/api/v1/analytics/trend?from=2016-01-01&to=2025-12-31&interval=MONTH'],
    ['analytics/trend 5y quarterly', '/api/v1/analytics/trend?from=2021-01-01&to=2025-12-31&interval=QUARTER'],
];

function percentile(sorted, p) {
    // Nearest-rank: the smallest value with at least p% of samples at or below it.
    const rank = Math.ceil((p / 100) * sorted.length);
    return sorted[Math.max(0, rank - 1)];
}

const rows = [];
for (const [label, path] of endpoints) {
    const times = [];
    let status = 0;
    for (let i = 0; i < warmup + iterations; i++) {
        const t0 = performance.now();
        const res = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${token}` } });
        await res.arrayBuffer();
        const ms = performance.now() - t0;
        status = res.status;
        if (i >= warmup) times.push(ms);
    }
    times.sort((a, b) => a - b);
    rows.push({ label, status, p50: percentile(times, 50), p95: percentile(times, 95), max: times[times.length - 1] });
}

console.log(`\nBase URL: ${base}   iterations: ${iterations} (after ${warmup} warm-up)\n`);
console.log('| Endpoint | HTTP | p50 ms | p95 ms | max ms |');
console.log('|---|---|---|---|---|');
for (const r of rows) {
    console.log(`| ${r.label} | ${r.status} | ${r.p50.toFixed(0)} | ${r.p95.toFixed(0)} | ${r.max.toFixed(0)} |`);
}
