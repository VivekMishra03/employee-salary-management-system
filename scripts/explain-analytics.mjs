// NFR-1 (server-side cost): runs EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) for the analytics and
// employee-list queries against the seeded Neon database and reports the "Execution Time" and
// "Planning Time" that the SERVER measured, i.e. without network latency between here and Neon.
//
// WARNING - THE SQL BELOW IS COPIED, NOT IMPORTED, AND CAN DRIFT from the Java sources. Re-check
// against them before trusting a result:
//   backend/src/main/java/com/acme/salary/readmodel/AnalyticsFilterSql.java  SLICE_FROM (l.33-38),
//       IN_FORCE_ON_AS_OF (l.40-41), no-filter WHERE assembled by build() (l.69-77 + l.87)
//   backend/src/main/java/com/acme/salary/readmodel/AnalyticsRepository.java  MEDIAN (l.156-157),
//       summary (l.171-180), byGroup (l.190-202), distribution (l.222-250)
//   backend/src/main/java/com/acme/salary/readmodel/GroupBy.java  key/label/extraJoin (l.9-11)
//   backend/src/main/java/com/acme/salary/readmodel/PayBandAnalyticsRepository.java  CLASSIFIED
//       (l.80-112), counts (l.123-129), rows (l.144-151, adherence == null)
//   backend/src/main/java/com/acme/salary/readmodel/GenderGapAnalyticsRepository.java  gaps (l.44-67)
//   backend/src/main/java/com/acme/salary/readmodel/TrendAnalyticsRepository.java  TREND (l.70-132),
//       no-filter WHERE = buildFiltersOnly -> "TRUE" (AnalyticsFilterSql l.62-67)
// Employee list: JPA/Hibernate SQL is not extractable, so it is HAND-WRITTEN here to mirror
//   backend/src/main/java/com/acme/salary/service/EmployeeService.java (DEFAULT_SORT l.65 + trailing
//   id, l.105-117: ORDER BY last_name, first_name, id) and EmployeeSpecifications.java (l.62-67,
//   l.77-83: lower(first_name||' '||last_name||' '||employee_code||' '||email) LIKE :token ESCAPE '\').
//   It omits Hibernate's column list and aliasing, so it approximates but does not equal the real SQL.
// Spring named parameters (:name) are converted to positional ($n, typed with a cast); nothing else
// in the analytics SQL is changed. Java text blocks are dedented as javac does before %n$s formatting.
//
// Each EXPLAIN executes the query for real, but inside BEGIN READ ONLY ... ROLLBACK, so nothing is
// written. Credentials come from the gitignored root .env via seed.js's buildClientConfig and are
// never printed.

import pg from 'pg';
import dotenv from 'dotenv';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { buildClientConfig } from './seed.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.resolve(__dirname, '../.env');
if (fs.existsSync(envPath)) {
    dotenv.config({ path: envPath });
} else {
    dotenv.config();
}

const WARMUPS = 2;
const RUNS = 15;
const PLAN_PRINT_THRESHOLD_MS = 100;

// ---- text-block helpers ---------------------------------------------------------------------

// Mirrors javac's incidental-whitespace stripping: drop the leading newline, remove the common
// indentation of non-blank lines, keep the trailing newline.
function tb(s) {
    const lines = s.replace(/^\n/, '').split('\n');
    const indents = lines.filter((l) => l.trim() !== '').map((l) => l.match(/^ */)[0].length);
    const min = Math.min(...indents);
    return lines.map((l) => l.slice(Math.min(min, l.length))).join('\n');
}

// Java's String.formatted with %1$s style arguments.
function fmt(template, ...args) {
    return template.replace(/%(\d+)\$s/g, (_, n) => args[Number(n) - 1]);
}

// ---- shared SQL fragments (AnalyticsFilterSql, GroupBy) -------------------------------------

const SLICE_FROM = tb(`
            FROM employee e
            JOIN salary_record s ON s.employee_id = e.id
            JOIN location l ON l.id = e.location_id
            JOIN job_role jr ON jr.id = e.job_role_id
            `);

const IN_FORCE_ON_AS_OF =
    's.effective_from <= :asOf AND (s.effective_to IS NULL OR s.effective_to > :asOf)';

// AnalyticsFilterSql.build(emptyFilter, asOf).where(): in-force predicate + default TERMINATED exclusion.
const WHERE_SNAPSHOT = IN_FORCE_ON_AS_OF + " AND e.employment_status <> 'TERMINATED'";
// AnalyticsFilterSql.buildFiltersOnly(emptyFilter).where()
const WHERE_TREND = 'TRUE';

const AMOUNT = 's.annualised_amount_base_ccy';
const MEDIAN = 'ROUND(CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY ' + AMOUNT + ') AS NUMERIC), 2)';

const GROUP_BY = {
    DEPARTMENT: { key: 'CAST(d.id AS VARCHAR)', label: 'd.name', join: 'JOIN department d ON d.id = e.department_id' },
    COUNTRY: { key: 'l.country_code', label: 'l.country_name', join: '' },
    JOB_LEVEL: { key: 'jr.job_level', label: 'jr.job_level', join: '' }
};

// ---- AnalyticsRepository --------------------------------------------------------------------

const SUMMARY = fmt(tb(`
                SELECT COUNT(*) AS headcount,
                       ROUND(COALESCE(SUM(%1$s * e.fte_ratio), 0), 2) AS total_payroll,
                       ROUND(AVG(%1$s), 2) AS mean,
                       ROUND(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY %1$s) AS NUMERIC), 2) AS p25,
                       %2$s AS median,
                       ROUND(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY %1$s) AS NUMERIC), 2) AS p75
                %3$s
                WHERE %4$s
                `), AMOUNT, MEDIAN, SLICE_FROM, WHERE_SNAPSHOT);

function byGroupSql(g) {
    return fmt(tb(`
                SELECT %1$s AS group_key,
                       %2$s AS group_label,
                       COUNT(*) AS headcount,
                       %3$s AS median,
                       ROUND(AVG(%4$s), 2) AS mean
                %5$s
                %6$s
                WHERE %7$s
                GROUP BY %1$s, %2$s
                ORDER BY group_label, group_key
                `), g.key, g.label, MEDIAN, AMOUNT, SLICE_FROM, g.join, WHERE_SNAPSHOT);
}

const DISTRIBUTION = fmt(tb(`
                WITH slice AS (
                    SELECT %1$s AS amount
                    %2$s
                    WHERE %3$s
                ), bounds AS (
                    SELECT MIN(amount) AS lo, MAX(amount) AS hi, COUNT(*) AS n FROM slice
                ), sized AS (
                    SELECT lo, hi, n, CASE WHEN hi = lo THEN 1 ELSE :buckets END AS k FROM bounds
                ), edges AS (
                    SELECT g.idx, z.k,
                           ROUND(z.lo + (g.idx - 1) * (z.hi - z.lo) / z.k, 2) AS lower_bound,
                           CASE WHEN g.idx = z.k THEN z.hi
                                ELSE ROUND(z.lo + g.idx * (z.hi - z.lo) / z.k, 2) END AS upper_bound
                    FROM sized z
                    CROSS JOIN LATERAL generate_series(1, z.k) AS g(idx)
                    WHERE z.n > 0
                ), counts AS (
                    SELECT e.idx, COUNT(*) AS cnt
                    FROM slice sl
                    JOIN edges e ON sl.amount >= e.lower_bound
                                AND (sl.amount < e.upper_bound OR (e.idx = e.k AND sl.amount <= e.upper_bound))
                    GROUP BY e.idx
                )
                SELECT e.lower_bound, e.upper_bound, COALESCE(c.cnt, 0) AS bucket_count
                FROM edges e
                LEFT JOIN counts c ON c.idx = e.idx
                ORDER BY e.idx
                `), AMOUNT, SLICE_FROM, WHERE_SNAPSHOT);

// ---- PayBandAnalyticsRepository -------------------------------------------------------------

const CLASSIFIED_TEMPLATE = tb(`
            WITH classified AS (
                SELECT e.id AS employee_id,
                       e.employee_code,
                       e.first_name || ' ' || e.last_name AS full_name,
                       jr.title AS job_title,
                       jr.job_level,
                       l.country_code,
                       s.currency_code,
                       s.annualised_amount AS amount,
                       b.min_amount,
                       b.mid_amount,
                       b.max_amount,
                       ROUND(s.annualised_amount / NULLIF(b.mid_amount, 0), 2) AS compa_ratio,
                       CASE WHEN b.min_amount IS NULL THEN 'NO_BAND'
                            WHEN s.annualised_amount < b.min_amount THEN 'BELOW'
                            WHEN s.annualised_amount > b.max_amount THEN 'ABOVE'
                            ELSE 'WITHIN' END AS adherence
                %1$s
                LEFT JOIN LATERAL (
                    SELECT pb.min_amount, pb.mid_amount, pb.max_amount
                    FROM pay_band pb
                    WHERE pb.job_role_id = e.job_role_id
                      AND pb.location_id = e.location_id
                      AND pb.currency_code = s.currency_code
                      AND pb.effective_from <= :asOf
                      AND (pb.effective_to IS NULL OR pb.effective_to > :asOf)
                    ORDER BY pb.effective_from DESC
                    LIMIT 1
                ) b ON TRUE
                WHERE %2$s
            )
            `);
const CLASSIFIED = fmt(CLASSIFIED_TEMPLATE, SLICE_FROM, WHERE_SNAPSHOT);

const PAYBAND_COUNTS = CLASSIFIED + tb(`
                SELECT COUNT(*) FILTER (WHERE adherence = 'BELOW') AS below,
                       COUNT(*) FILTER (WHERE adherence = 'WITHIN') AS within_band,
                       COUNT(*) FILTER (WHERE adherence = 'ABOVE') AS above,
                       COUNT(*) FILTER (WHERE adherence = 'NO_BAND') AS no_band
                FROM classified
                `);

// rows() with adherence == null: the optional WHERE adherence = :adherence line is omitted.
const PAYBAND_PAGE = CLASSIFIED + tb(`
                SELECT employee_id, employee_code, full_name, job_title, job_level, country_code,
                       currency_code, amount, min_amount, mid_amount, max_amount, compa_ratio, adherence
                FROM classified
                `) + tb(`
                ORDER BY compa_ratio ASC NULLS LAST, employee_code ASC
                LIMIT :limit OFFSET :offset
                `);

// ---- GenderGapAnalyticsRepository -----------------------------------------------------------

function genderGapSql(g) {
    return fmt(tb(`
                WITH agg AS (
                    SELECT %1$s AS group_key,
                           %2$s AS group_label,
                           COUNT(*) FILTER (WHERE e.gender = 'MALE') AS male_count,
                           COUNT(*) FILTER (WHERE e.gender = 'FEMALE') AS female_count,
                           AVG(%3$s) FILTER (WHERE e.gender = 'MALE') AS mean_m,
                           AVG(%3$s) FILTER (WHERE e.gender = 'FEMALE') AS mean_f,
                           ROUND(CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY %3$s)
                                 FILTER (WHERE e.gender = 'MALE') AS NUMERIC), 2) AS median_m,
                           ROUND(CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY %3$s)
                                 FILTER (WHERE e.gender = 'FEMALE') AS NUMERIC), 2) AS median_f
                    %4$s
                    %5$s
                    WHERE %6$s AND e.gender IN ('MALE', 'FEMALE')
                    GROUP BY %1$s, %2$s
                )
                SELECT group_key, group_label, male_count, female_count,
                       ROUND((mean_m - mean_f) / NULLIF(mean_m, 0) * 100, 2) AS mean_gap_pct,
                       ROUND((median_m - median_f) / NULLIF(median_m, 0) * 100, 2) AS median_gap_pct
                FROM agg
                ORDER BY group_label, group_key
                `), g.key, g.label, AMOUNT, SLICE_FROM, g.join, WHERE_SNAPSHOT);
}

// ---- TrendAnalyticsRepository ---------------------------------------------------------------

const TREND = fmt(tb(`
            WITH periods AS (
                SELECT CAST(gs AS DATE) AS bucket_start
                FROM generate_series(CAST(:firstBucket AS TIMESTAMP), CAST(:rangeTo AS TIMESTAMP),
                                     make_interval(months => :months)) AS gs
            ), contribution AS (
                SELECT GREATEST(s.effective_from, e.hire_date, CAST(:firstBucket AS DATE)) AS from_day,
                       LEAST(s.effective_to, e.termination_date) AS until_day,
                       s.annualised_amount_base_ccy * e.fte_ratio AS amount
                FROM employee e
                JOIN salary_record s ON s.employee_id = e.id
                JOIN location l ON l.id = e.location_id
                JOIN job_role jr ON jr.id = e.job_role_id
                WHERE %1$s
            ), payroll_events AS (
                SELECT from_day AS event_day, amount AS delta
                FROM contribution
                WHERE from_day <= CAST(:rangeTo AS DATE) AND (until_day IS NULL OR from_day < until_day)
                UNION ALL
                SELECT until_day, -amount
                FROM contribution
                WHERE until_day <= CAST(:rangeTo AS DATE) AND from_day < until_day
            ), payroll_steps AS (
                SELECT make_date(CAST(EXTRACT(YEAR FROM event_day) AS INT),
                                 (CAST(EXTRACT(MONTH FROM event_day) AS INT) - 1) / :months * :months + 1, 1) AS bucket_start,
                       SUM(delta) AS delta
                FROM payroll_events
                GROUP BY 1
            ), payroll AS (
                SELECT p.bucket_start,
                       SUM(COALESCE(d.delta, 0)) OVER (ORDER BY p.bucket_start) AS total
                FROM periods p
                LEFT JOIN payroll_steps d ON d.bucket_start = p.bucket_start
            ), changes AS (
                SELECT employee_id, effective_from, change_reason, currency_code, annualised_amount,
                       LAG(annualised_amount) OVER w AS prev_amount,
                       LAG(currency_code) OVER w AS prev_currency
                FROM salary_record
                WHERE effective_to IS NULL OR effective_from < effective_to
                WINDOW w AS (PARTITION BY employee_id ORDER BY effective_from)
            ), increases AS (
                SELECT make_date(CAST(EXTRACT(YEAR FROM s.effective_from) AS INT),
                                 (CAST(EXTRACT(MONTH FROM s.effective_from) AS INT) - 1) / :months * :months + 1, 1) AS bucket_start,
                       AVG((s.annualised_amount - s.prev_amount) / s.prev_amount * 100) AS avg_pct
                FROM changes s
                JOIN employee e ON e.id = s.employee_id
                JOIN location l ON l.id = e.location_id
                JOIN job_role jr ON jr.id = e.job_role_id
                WHERE %1$s
                  AND s.effective_from BETWEEN CAST(:rangeFrom AS DATE) AND CAST(:rangeTo AS DATE)
                  AND s.change_reason NOT IN ('NEW_HIRE', 'CORRECTION')
                  AND s.prev_amount > 0
                  AND s.prev_currency = s.currency_code
                GROUP BY 1
            )
            SELECT p.bucket_start,
                   COALESCE(ROUND(pay.total, 2), 0.00) AS total_payroll,
                   ROUND(i.avg_pct, 2) AS avg_increase_pct
            FROM periods p
            LEFT JOIN payroll pay ON pay.bucket_start = p.bucket_start
            LEFT JOIN increases i ON i.bucket_start = p.bucket_start
            ORDER BY p.bucket_start
            `), WHERE_TREND);

// TrendInterval.bucketStart: first day of the calendar bucket containing the date.
function bucketStart(dateStr, months) {
    const [y, m] = dateStr.split('-').map(Number);
    const firstMonth = Math.floor((m - 1) / months) * months + 1;
    return `${y}-${String(firstMonth).padStart(2, '0')}-01`;
}

function trendParams(from, to, months) {
    return { firstBucket: bucketStart(from, months), rangeFrom: from, rangeTo: to, months };
}

// ---- employee list (hand-written, see header) -----------------------------------------------

const SEARCH_TEXT = "lower(first_name || ' ' || last_name || ' ' || employee_code || ' ' || email)";
const EMP_PAGE = 'SELECT * FROM employee ORDER BY last_name, first_name, id LIMIT :limit OFFSET :offset';
const EMP_COUNT = 'SELECT count(*) FROM employee';
const EMP_SEARCH_WHERE = SEARCH_TEXT + " LIKE :q0 ESCAPE '\\'";
const EMP_SEARCH_COUNT = 'SELECT count(*) FROM employee WHERE ' + EMP_SEARCH_WHERE;
const EMP_SEARCH_PAGE = 'SELECT * FROM employee WHERE ' + EMP_SEARCH_WHERE
    + ' ORDER BY last_name, first_name, id LIMIT :limit OFFSET :offset';

// ---- query catalogue ------------------------------------------------------------------------

const AS_OF = { asOf: '2025-12-31' };

const QUERIES = [
    { group: 'analytics', name: 'summary', sql: SUMMARY, params: AS_OF },
    { group: 'analytics', name: 'by-group DEPARTMENT', sql: byGroupSql(GROUP_BY.DEPARTMENT), params: AS_OF },
    { group: 'analytics', name: 'by-group COUNTRY', sql: byGroupSql(GROUP_BY.COUNTRY), params: AS_OF },
    { group: 'analytics', name: 'by-group JOB_LEVEL', sql: byGroupSql(GROUP_BY.JOB_LEVEL), params: AS_OF },
    { group: 'analytics', name: 'distribution 12 buckets', sql: DISTRIBUTION, params: { ...AS_OF, buckets: 12 } },
    { group: 'analytics', name: 'distribution 50 buckets', sql: DISTRIBUTION, params: { ...AS_OF, buckets: 50 } },
    { group: 'analytics', name: 'pay-band counts', sql: PAYBAND_COUNTS, params: AS_OF },
    { group: 'analytics', name: 'pay-band page (limit 25, offset 0)', sql: PAYBAND_PAGE, params: { ...AS_OF, limit: 25, offset: 0 } },
    { group: 'analytics', name: 'gender gap DEPARTMENT', sql: genderGapSql(GROUP_BY.DEPARTMENT), params: AS_OF },
    { group: 'analytics', name: 'gender gap JOB_LEVEL', sql: genderGapSql(GROUP_BY.JOB_LEVEL), params: AS_OF },
    { group: 'analytics', name: 'trend monthly 2021-2025 (60 buckets)', sql: TREND, params: trendParams('2021-01-01', '2025-12-31', 1) },
    { group: 'analytics', name: 'trend monthly 2016-2025 (120 buckets)', sql: TREND, params: trendParams('2016-01-01', '2025-12-31', 1) },
    { group: 'analytics', name: 'trend quarterly 2016-2025 (40 buckets)', sql: TREND, params: trendParams('2016-01-01', '2025-12-31', 3) },
    { group: 'employee list', name: 'page 1 (limit 25, offset 0)', sql: EMP_PAGE, params: { limit: 25, offset: 0 } },
    { group: 'employee list', name: 'page 301 (limit 25, offset 7500)', sql: EMP_PAGE, params: { limit: 25, offset: 7500 } },
    { group: 'employee list', name: 'count(*)', sql: EMP_COUNT, params: {} },
    { group: 'employee list', name: "search 'smith' count", sql: EMP_SEARCH_COUNT, params: { q0: '%smith%' } },
    { group: 'employee list', name: "search 'smith' page (limit 25, offset 0)", sql: EMP_SEARCH_PAGE, params: { q0: '%smith%', limit: 25, offset: 0 } }
];

// ---- named -> positional --------------------------------------------------------------------

const PARAM_CAST = {
    asOf: '::date', rangeFrom: '::date', rangeTo: '::date', firstBucket: '::date',
    months: '::int', buckets: '::int', limit: '::int', offset: '::bigint'
};

function toPositional(sql, params) {
    const order = [];
    const text = sql.replace(/(?<!:):([A-Za-z_]\w*)/g, (_, name) => {
        if (!(name in params)) {
            throw new Error(`missing parameter :${name}`);
        }
        let idx = order.indexOf(name) + 1;
        if (idx === 0) {
            order.push(name);
            idx = order.length;
        }
        return `$${idx}${PARAM_CAST[name] ?? ''}`;
    });
    return { text, values: order.map((n) => String(params[n])) };
}

// ---- measurement ----------------------------------------------------------------------------

function parseTimes(planText) {
    const exec = /Execution Time: ([\d.]+) ms/.exec(planText);
    const plan = /Planning Time: ([\d.]+) ms/.exec(planText);
    if (!exec || !plan) {
        throw new Error('EXPLAIN output had no Planning/Execution Time line');
    }
    return { exec: Number(exec[1]), plan: Number(plan[1]) };
}

async function explainOnce(client, text, values) {
    await client.query('BEGIN READ ONLY');
    try {
        const res = await client.query(`EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) ${text}`, values);
        const planText = res.rows.map((r) => r['QUERY PLAN']).join('\n');
        return { ...parseTimes(planText), planText };
    } finally {
        await client.query('ROLLBACK');
    }
}

// Nearest-rank percentile. With 15 runs, p95 is rank ceil(0.95 * 15) = 15, i.e. the maximum.
function percentile(sorted, p) {
    return sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)];
}

const f = (n) => n.toFixed(1);

async function main() {
    const client = new pg.Client(buildClientConfig(process.env));
    await client.connect();
    await client.query("SET statement_timeout = '120s'");

    const version = (await client.query('SHOW server_version')).rows[0].server_version;
    console.log(`PostgreSQL server_version: ${version}`);
    for (const t of ['employee', 'salary_record', 'pay_band']) {
        const n = (await client.query(`SELECT count(*) AS n FROM ${t}`)).rows[0].n;
        console.log(`rows in ${t}: ${n}`);
    }
    console.log(`\nRuns per query: ${RUNS} after ${WARMUPS} warm-ups (asOf 2025-12-31, no user filters). ` +
        `p95 is nearest-rank; with ${RUNS} samples it equals the max.\n`);

    // Optional CLI filter: `node explain-analytics.mjs trend` runs only queries whose group/name contains it.
    const only = (process.argv[2] ?? "").toLowerCase();
    const selected = QUERIES.filter((q) => `${q.group} ${q.name}`.toLowerCase().includes(only));
    if (selected.length === 0) {
        throw new Error(`no query matches filter "${only}"`);
    }

    const results = [];
    for (const q of selected) {
        const { text, values } = toPositional(q.sql, q.params);
        const first = await explainOnce(client, text, values); // warm-up 1 kept as the "first run" figure
        for (let i = 1; i < WARMUPS; i++) {
            await explainOnce(client, text, values);
        }
        const execs = [];
        const plans = [];
        let slowest = null;
        for (let i = 0; i < RUNS; i++) {
            const r = await explainOnce(client, text, values);
            execs.push(r.exec);
            plans.push(r.plan);
            if (!slowest || r.exec > slowest.exec) {
                slowest = r;
            }
        }
        execs.sort((a, b) => a - b);
        plans.sort((a, b) => a - b);
        results.push({
            q, first: first.exec,
            e50: percentile(execs, 0.5), e95: percentile(execs, 0.95), emax: execs[execs.length - 1],
            p50: percentile(plans, 0.5), p95: percentile(plans, 0.95), pmax: plans[plans.length - 1],
            slowestPlan: slowest.planText
        });
        process.stderr.write(`done: ${q.name}\n`);
    }
    await client.end();

    console.log('| group | query | first run exec ms | exec p50 | exec p95 | exec max | plan p50 | plan p95 | plan max |');
    console.log('|---|---|---:|---:|---:|---:|---:|---:|---:|');
    for (const r of results) {
        console.log(`| ${r.q.group} | ${r.q.name} | ${f(r.first)} | ${f(r.e50)} | ${f(r.e95)} | ${f(r.emax)} | ` +
            `${f(r.p50)} | ${f(r.p95)} | ${f(r.pmax)} |`);
    }

    const slow = results.filter((r) => r.e95 > PLAN_PRINT_THRESHOLD_MS).sort((a, b) => b.e95 - a.e95);
    if (slow.length === 0) {
        console.log(`\nNo query had an execution-time p95 above ${PLAN_PRINT_THRESHOLD_MS} ms; no plans printed.`);
    }
    for (const r of slow) {
        console.log(`\n### Plan (slowest of ${RUNS} runs, exec ${f(r.emax)} ms): ${r.q.group} / ${r.q.name}\n`);
        console.log('```');
        console.log(r.slowestPlan);
        console.log('```');
    }
}

main().catch((err) => {
    // Never print the connection config: only the message, with connection details scrubbed.
    let msg = String(err.message);
    let host = '';
    try {
        host = buildClientConfig(process.env).host;
    } catch (_) { /* config itself was the problem; nothing to scrub */ }
    for (const secret of [process.env.DATABASE_PASSWORD, process.env.DATABASE_USERNAME, host]) {
        if (secret) {
            msg = msg.split(secret).join('[redacted]');
        }
    }
    console.error(`explain-analytics failed: ${msg}`);
    process.exit(1);
});
