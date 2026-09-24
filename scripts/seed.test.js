import test from 'node:test';
import assert from 'node:assert/strict';
import bcrypt from 'bcryptjs';
import {
    generateDataset, buildClientConfig, orderManagersFirst, createRng,
    hashHrPassword, requireHrPassword, buildResetPasswordQuery, PLACEHOLDER_PASSWORD_HASH,
    DEPARTMENTS, LOCATIONS, JOB_ROLES, PAY_BANDS, EXCHANGE_RATES,
    parseCents, parseRate, formatCents, roundHalfUp, annualise, convertToBase, findPayBand, payBandAdherence,
    buildInsert, insertBatch, tableSpecs, escapeSql, renderInsertLiteral, formatDbError,
    parseArgs, WIPED_TABLES, buildTruncateStatement, USAGE
} from './seed.js';

// ---------------------------------------------------------------------------------------------
// The full 10,000-employee dataset is generated once and only read afterwards (no test mutates it).
// ---------------------------------------------------------------------------------------------
let fullDatasetCache;
const fullDataset = () => (fullDatasetCache ??= generateDataset());

// ---------------------------------------------------------------------------------------------
// NFR-2: exact money arithmetic. The seed's helpers mirror SalaryCalculator.java (backend): amounts
// are BigInt cents, FX rates are BigInt scaled by 1e8, and every result is rounded HALF_UP once.
// The vectors below are copied from SalaryCalculatorTest.java.
// ---------------------------------------------------------------------------------------------

const annualStr = (amount, frequency) => formatCents(annualise(parseCents(amount), frequency));
const convertStr = (amount, rate) => formatCents(convertToBase(parseCents(amount), parseRate(rate)));

test('FR-3.4: annualise leaves an ANNUAL amount unchanged', () => {
    assert.equal(annualStr('120000.00', 'ANNUAL'), '120000.00');
});

test('requirements.md 6.2: annualise multiplies a MONTHLY amount by 12', () => {
    assert.equal(annualStr('8333.33', 'MONTHLY'), '99999.96');
});

test('requirements.md 6.2: annualise multiplies an HOURLY amount by 2080', () => {
    assert.equal(annualStr('45.50', 'HOURLY'), '94640.00');
});

test('NFR-2: annualise output always has exactly two decimal places', () => {
    assert.equal(annualStr('100000', 'ANNUAL'), '100000.00');
    assert.equal(annualStr('1', 'MONTHLY'), '12.00');
    assert.equal(annualStr('0.01', 'HOURLY'), '20.80');
});

test('NFR-2: annualise rejects an unknown pay frequency instead of defaulting', () => {
    assert.throws(() => annualise(100n, 'WEEKLY'), /WEEKLY/);
});

test('FR-3.5: convertToBase multiplies by the rate and rounds to cents', () => {
    assert.equal(convertStr('100000.00', '1.08000000'), '108000.00');
});

test('NFR-2: convertToBase uses the rate at full eight-decimal precision, rounded once', () => {
    assert.equal(convertStr('99999.96', '0.01197354'), '1197.35');
});

test('NFR-2: convertToBase rounds an exact half UP (0.125 -> 0.13, not the HALF_EVEN 0.12)', () => {
    assert.equal(convertStr('100.00', '0.00125000'), '0.13');
});

test('NFR-2: convertToBase rounds just below a half down', () => {
    assert.equal(convertStr('100.00', '0.00124999'), '0.12');
});

test('NFR-2: convertToBase output always has exactly two decimal places', () => {
    assert.equal(convertStr('100000.00', '1.00000000'), '100000.00');
});

test('NFR-2: convertToBase refuses a zero or negative rate', () => {
    assert.throws(() => convertToBase(10000n, 0n), /rate/i);
    assert.throws(() => convertToBase(10000n, -1n), /rate/i);
});

test('NFR-2: roundHalfUp rounds the exact half away from zero and is exact for BigInt', () => {
    assert.equal(roundHalfUp(5n, 10n), 1n);
    assert.equal(roundHalfUp(4n, 10n), 0n);
    assert.equal(roundHalfUp(15n, 10n), 2n);
    assert.equal(roundHalfUp(-5n, 10n), -1n);
    assert.equal(roundHalfUp(-4n, 10n), 0n);
    assert.equal(roundHalfUp(10n ** 30n + 5n, 10n), 10n ** 29n + 1n);
    assert.throws(() => roundHalfUp(1n, 0n));
});

test('NFR-2: parseCents and parseRate refuse anything that would silently lose precision', () => {
    assert.equal(parseCents('1234.50'), 123450n);
    assert.equal(parseCents('100000'), 10000000n);
    assert.equal(parseRate('0.01197354'), 1197354n);
    assert.throws(() => parseCents('1.234'), /1\.234/);
    assert.throws(() => parseCents('-1.00'));
    assert.throws(() => parseCents('1e3'));
    assert.throws(() => parseRate('0.123456789'));
    assert.equal(formatCents(5n), '0.05');
    assert.equal(formatCents(123450n), '1234.50');
});

// Independent oracle: no BigInt rounding helper from the seed. Both operands are turned into digit
// strings by deleting the decimal point, multiplied as BigInt, and rounded by *inspecting the first
// dropped digit of the decimal string* (HALF_UP looks at that digit only), not by integer division.
function oracleConvert(amountStr, rateStr) {
    const [ai, af = ''] = amountStr.split('.');
    const [ri, rf = ''] = rateStr.split('.');
    const product = (BigInt(ai + af.padEnd(2, '0')) * BigInt(ri + rf.padEnd(8, '0'))).toString().padStart(11, '0');
    const kept = product.slice(0, -8);
    const firstDropped = product.slice(-8)[0];
    const cents = (BigInt(kept) + (firstDropped >= '5' ? 1n : 0n)).toString().padStart(3, '0');
    return `${cents.slice(0, -2)}.${cents.slice(-2)}`;
}

test('NFR-2: convertToBase agrees with an independent string-based oracle on 20,000 seeded random cases', () => {
    const rand = createRng(7);
    const digits = (n) => Array.from({ length: n }, () => Math.floor(rand() * 10)).join('');
    for (let i = 0; i < 20000; i++) {
        const amount = `${BigInt(digits(1 + Math.floor(rand() * 9)))}.${digits(2)}`;
        const rate = `${Math.floor(rand() * 90)}.${digits(8)}`;
        if (BigInt(rate.replace('.', '')) === 0n || BigInt(amount.replace('.', '')) === 0n) continue;
        assert.equal(convertStr(amount, rate), oracleConvert(amount, rate), `${amount} x ${rate}`);
    }
});

test('NFR-2: convertToBase rounds every exact half UP (cents = 4 mod 8 at rate 0.125 is always a .5 tie)', () => {
    const rand = createRng(7);
    for (let i = 0; i < 2000; i++) {
        const cents = BigInt(Math.floor(rand() * 1e9)) * 8n + 4n;
        const amount = formatCents(cents);
        const got = convertStr(amount, '0.12500000');
        assert.equal(got, oracleConvert(amount, '0.12500000'), amount);
        // A tie: 4 mod 8 cents x 0.125 has a fractional part of exactly one half, so the result is
        // the floor plus one cent, never the floor.
        assert.equal(parseCents(got), (cents * 125n) / 1000n + 1n, amount);
    }
});

test('NFR-2: the oracle itself reproduces the SalaryCalculatorTest vectors (so it is a fair judge)', () => {
    assert.equal(oracleConvert('99999.96', '0.01197354'), '1197.35');
    assert.equal(oracleConvert('100.00', '0.00125000'), '0.13');
    assert.equal(oracleConvert('100.00', '0.00124999'), '0.12');
});

test('FR-6.1: generateDataset() with no arguments produces exactly 10,000 employees', () => {
    const data = fullDataset();
    assert.equal(data.employees.length, 10000);
    assert.equal(new Set(data.employees.map(e => e.id)).size, 10000);
});

test('FR-6.1: the reference data has 8 departments, 6 locations, 48 job roles, 288 pay bands and 28 exchange rates', () => {
    const data = generateDataset(50);
    assert.equal(data.departments.length, 8);
    assert.equal(data.locations.length, 6);
    assert.equal(data.jobRoles.length, 48);
    assert.equal(data.payBands.length, 288);
    assert.equal(data.exchangeRates.length, 28);
});

test('FR-6.1: a 1,000-employee dataset (fast unit-test size) has 1,000 employees and at least 2,500 salary records', () => {
    const data = generateDataset(1000);
    assert.equal(data.employees.length, 1000);
    assert.ok(data.salaries.length >= 2500, `Expected at least 2,500 salaries for 1,000 employees, got ${data.salaries.length}`);
});

test('FR-6.1: there is exactly one app_user, the HR Manager account hr.manager@acme.com', () => {
    const data = generateDataset(50);
    assert.equal(data.appUsers.length, 1);
    assert.equal(data.appUsers[0].email, 'hr.manager@acme.com');
    assert.equal(data.appUsers[0].role, 'HR_MANAGER');
});

test('NFR-4: without a supplied hash the HR app_user carries a BCrypt-format ($2a$10$ / $2b$10$) password hash', () => {
    const hash = generateDataset(50).appUsers[0].passwordHash;
    assert.ok(hash.startsWith('$2a$10$') || hash.startsWith('$2b$10$'), 'expected a cost-10 BCrypt-format string');
});

test('FR-6.2: the same seed yields identical employees and salaries arrays; a different seed does not', () => {
    const data1 = generateDataset(300);
    const data2 = generateDataset(300);
    assert.deepEqual(data1.employees, data2.employees);
    assert.deepEqual(data1.salaries, data2.salaries);

    const other = generateDataset(300, { seed: 43 });
    assert.notDeepEqual(other.employees, data1.employees, 'a different seed must give a different dataset');
    assert.notDeepEqual(other.salaries, data1.salaries, 'a different seed must give different salaries');
});

test('FR-3.2: salary records for each employee form contiguous, non-overlapping intervals [from, to)', () => {
    const data = generateDataset(500);

    // Group salaries by employeeId
    const salariesByEmp = new Map();
    for (const s of data.salaries) {
        if (!salariesByEmp.has(s.employeeId)) {
            salariesByEmp.set(s.employeeId, []);
        }
        salariesByEmp.get(s.employeeId).push(s);
    }

    const empMap = new Map(data.employees.map(e => [e.id, e]));

    for (const [empId, empSalaries] of salariesByEmp.entries()) {
        const emp = empMap.get(empId);
        assert.ok(emp, `Employee ${empId} must exist`);

        // First salary record must start on hire_date
        assert.equal(empSalaries[0].effectiveFrom, emp.hireDate, `Emp ${empId} first salary must start on hireDate`);

        for (let i = 0; i < empSalaries.length; i++) {
            const current = empSalaries[i];

            // Money format checks (NFR-2)
            assert.match(current.baseAmount, /^\d+\.\d{2}$/, 'baseAmount must be 2-decimal money');
            assert.match(current.annualisedAmount, /^\d+\.\d{2}$/, 'annualisedAmount must be 2-decimal money');
            assert.match(current.annualisedAmountBaseCcy, /^\d+\.\d{2}$/, 'annualisedAmountBaseCcy must be 2-decimal money');
            assert.ok(parseCents(current.baseAmount) > 0n, 'baseAmount must be positive');

            if (i < empSalaries.length - 1) {
                // Non-final record must have effectiveTo matching next record's effectiveFrom
                const next = empSalaries[i + 1];
                assert.notEqual(current.effectiveTo, null, `Emp ${empId} record ${i} must not be open-ended`);
                assert.equal(current.effectiveTo, next.effectiveFrom, `Emp ${empId} interval ${i} must seamlessly meet interval ${i+1}`);
                assert.ok(current.effectiveFrom < current.effectiveTo, `Emp ${empId} interval [${current.effectiveFrom}, ${current.effectiveTo}) must be positive`);
            } else {
                // Final record must be open-ended (effectiveTo = null) per FR-3.6
                assert.equal(current.effectiveTo, null, `Emp ${empId} final record must have effectiveTo = null`);
            }
        }
    }
});

test('FR-2.1 / FR-2.4: employee integrity and constraint invariants', () => {
    const data = generateDataset(500);

    const deptIds = new Set(data.departments.map(d => d.id));
    const locIds = new Set(data.locations.map(l => l.id));
    const roleIds = new Set(data.jobRoles.map(r => r.id));
    const empIds = new Set(data.employees.map(e => e.id));

    for (const emp of data.employees) {
        assert.ok(deptIds.has(emp.departmentId), `Invalid departmentId ${emp.departmentId}`);
        assert.ok(locIds.has(emp.locationId), `Invalid locationId ${emp.locationId}`);
        assert.ok(roleIds.has(emp.jobRoleId), `Invalid jobRoleId ${emp.jobRoleId}`);

        // Invariant: manager_id <> id
        assert.notEqual(emp.managerId, emp.id, `Emp ${emp.id} cannot manage themselves`);
        if (emp.managerId !== null) {
            assert.ok(empIds.has(emp.managerId), `Manager ${emp.managerId} must exist`);
        }

        // Invariant: employment_status = 'TERMINATED' iff termination_date IS NOT NULL
        if (emp.employmentStatus === 'TERMINATED') {
            assert.notEqual(emp.terminationDate, null, `Terminated emp ${emp.id} must have terminationDate`);
            assert.ok(emp.terminationDate >= emp.hireDate, `Emp ${emp.id} terminationDate must be >= hireDate`);
        } else {
            assert.equal(emp.terminationDate, null, `Active/on-leave emp ${emp.id} must have null terminationDate`);
        }

        // Invariant: fte_ratio valid decimal between 0.1 and 1.0
        const fte = parseFloat(emp.fteRatio);
        assert.ok(fte >= 0.1 && fte <= 1.0, `FTE ratio ${fte} out of range`);
    }
});

// FR-6: the seed must authenticate with the credentials held in DATABASE_USERNAME/DATABASE_PASSWORD
// (.env.example keeps them out of the URL). pg 8.23 lets a parsed connection string overwrite an
// explicit password with null when the URL carries none, which fails as "client password must be a
// string" -- so the config is built from explicit fields and never passes a connection string.
test('FR-6: buildClientConfig uses explicit fields so the password from the environment survives', () => {
    const cfg = buildClientConfig({
        DATABASE_URL: 'jdbc:postgresql://ep-example-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require',
        DATABASE_USERNAME: 'neondb_owner',
        DATABASE_PASSWORD: 'p@ss:w/rd#1',
    });

    assert.equal(cfg.host, 'ep-example-pooler.us-east-2.aws.neon.tech');
    assert.equal(cfg.port, 5432);
    assert.equal(cfg.database, 'neondb');
    assert.equal(cfg.user, 'neondb_owner');
    assert.equal(cfg.password, 'p@ss:w/rd#1');
    assert.equal(typeof cfg.password, 'string');
    assert.equal(cfg.connectionString, undefined);
    assert.equal(cfg.ssl, true);
});

test('FR-6: buildClientConfig honours an explicit port and a plain postgresql:// URL', () => {
    const cfg = buildClientConfig({
        DATABASE_URL: 'postgresql://db.internal:6543/acme',
        DATABASE_USERNAME: 'u',
        DATABASE_PASSWORD: 'p',
    });
    assert.equal(cfg.host, 'db.internal');
    assert.equal(cfg.port, 6543);
    assert.equal(cfg.database, 'acme');
});

test('FR-6: buildClientConfig refuses to run without a URL, username or password', () => {
    const full = { DATABASE_URL: 'jdbc:postgresql://h/db', DATABASE_USERNAME: 'u', DATABASE_PASSWORD: 'p' };
    assert.throws(() => buildClientConfig({ ...full, DATABASE_URL: '' }), /DATABASE_URL/);
    assert.throws(() => buildClientConfig({ ...full, DATABASE_USERNAME: undefined }), /DATABASE_USERNAME/);
    assert.throws(() => buildClientConfig({ ...full, DATABASE_PASSWORD: '' }), /DATABASE_PASSWORD/);
});

// fk_employee_manager is an immediate (non-deferrable) foreign key, so a manager row must be inserted
// before any row that references it. Employee ids follow creation order, not hierarchy, so inserting in
// id order failed on a real database with "Key (manager_id)=(1532) is not present in table employee".
test('FR-6: orderManagersFirst places every manager before all of their reports', () => {
    const data = generateDataset(2000);
    const ordered = orderManagersFirst(data.employees);

    assert.equal(ordered.length, data.employees.length);
    assert.equal(new Set(ordered.map(e => e.id)).size, data.employees.length, 'no employee lost or duplicated');

    const position = new Map(ordered.map((e, i) => [e.id, i]));
    for (const emp of ordered) {
        if (emp.managerId !== null) {
            assert.ok(position.get(emp.managerId) < position.get(emp.id),
                `manager ${emp.managerId} must be inserted before report ${emp.id}`);
        }
    }
});

test('FR-6: orderManagersFirst is deterministic and does not mutate its input', () => {
    const data = generateDataset(300);
    const before = data.employees.map(e => e.id);

    const first = orderManagersFirst(data.employees).map(e => e.id);
    const second = orderManagersFirst(data.employees).map(e => e.id);

    assert.deepEqual(first, second);
    assert.deepEqual(data.employees.map(e => e.id), before);
});

test('FR-6: orderManagersFirst rejects a management cycle instead of looping forever', () => {
    const a = { id: 1, managerId: 2 };
    const b = { id: 2, managerId: 1 };
    assert.throws(() => orderManagersFirst([a, b]), /cycle/i);
});

test('FR-6: orderManagersFirst rejects a manager that does not exist', () => {
    assert.throws(() => orderManagersFirst([{ id: 1, managerId: 99 }]), /99/);
});

// NFR-4: the HR Manager password is never committed. It comes from SEED_HR_PASSWORD, is validated
// before any database contact, and its value must never appear in an error message.
// The values below are throwaway test literals, not credentials for any environment.
const TWELVE = 'abcdefghij12';

test('NFR-4: requireHrPassword rejects a missing SEED_HR_PASSWORD and names the variable', () => {
    assert.throws(() => requireHrPassword({}), /SEED_HR_PASSWORD/);
});

test('NFR-4: requireHrPassword rejects an empty SEED_HR_PASSWORD', () => {
    assert.throws(() => requireHrPassword({ SEED_HR_PASSWORD: '' }), /SEED_HR_PASSWORD/);
});

test('NFR-4: requireHrPassword rejects an 11-character password', () => {
    assert.throws(() => requireHrPassword({ SEED_HR_PASSWORD: TWELVE.slice(0, 11) }), /at least 12/);
});

test('NFR-4: requireHrPassword accepts exactly 12 characters and returns the value', () => {
    assert.equal(requireHrPassword({ SEED_HR_PASSWORD: TWELVE }), TWELVE);
});

test('NFR-4: requireHrPassword accepts exactly 72 bytes', () => {
    const pw = 'a'.repeat(72);
    assert.equal(requireHrPassword({ SEED_HR_PASSWORD: pw }), pw);
});

test('NFR-4: requireHrPassword rejects 73 bytes (BCrypt would silently truncate)', () => {
    assert.throws(() => requireHrPassword({ SEED_HR_PASSWORD: 'a'.repeat(73) }), /72 bytes/);
});

test('NFR-4: requireHrPassword measures the 72-byte limit in bytes, not characters', () => {
    // 36 two-byte characters = 36 chars but 72 bytes (accepted); one more character = 74 bytes.
    const twoByte = 'é'.repeat(36);
    assert.equal(Buffer.byteLength(twoByte, 'utf8'), 72);
    assert.equal(requireHrPassword({ SEED_HR_PASSWORD: twoByte }), twoByte);
    assert.throws(() => requireHrPassword({ SEED_HR_PASSWORD: twoByte + 'é' }), /72 bytes/);
    // 70 characters in total, yet 69 ASCII bytes + one 4-byte emoji = 73 bytes.
    assert.throws(() => requireHrPassword({ SEED_HR_PASSWORD: 'a'.repeat(69) + '\u{1F600}' }), /72 bytes/);
});

test('NFR-4: requireHrPassword never puts the password value in an error message', () => {
    const tooLong = 'SecretValue-'.repeat(7);
    let message = '';
    try { requireHrPassword({ SEED_HR_PASSWORD: tooLong }); } catch (e) { message = e.message; }
    assert.ok(message.length > 0, 'expected a rejection');
    assert.ok(!message.includes('SecretValue'), 'the password must not be echoed');
    let shortMessage = '';
    try { requireHrPassword({ SEED_HR_PASSWORD: 'shortpw' }); } catch (e) { shortMessage = e.message; }
    assert.ok(!shortMessage.includes('shortpw'), 'the password must not be echoed');
});

test('NFR-4: hashHrPassword produces a cost-10 BCrypt hash that verifies with bcrypt.compare', async () => {
    const hash = hashHrPassword(TWELVE);
    assert.match(hash, /^\$2[ab]\$10\$/);
    assert.equal(await bcrypt.compare(TWELVE, hash), true);
    assert.equal(await bcrypt.compare(TWELVE + 'x', hash), false);
});

test('NFR-4: hashHrPassword salts randomly, so two hashes differ but both verify', async () => {
    const h1 = hashHrPassword(TWELVE);
    const h2 = hashHrPassword(TWELVE);
    assert.notEqual(h1, h2);
    assert.equal(await bcrypt.compare(TWELVE, h1), true);
    assert.equal(await bcrypt.compare(TWELVE, h2), true);
});

test('NFR-4: generateDataset uses the supplied hrPasswordHash for the HR app_user', () => {
    const hash = hashHrPassword(TWELVE);
    const data = generateDataset(50, { hrPasswordHash: hash });
    assert.equal(data.appUsers[0].passwordHash, hash);
});

test('NFR-4: without a supplied hash generateDataset uses a placeholder that verifies no password', async () => {
    const data = generateDataset(50);
    assert.equal(data.appUsers[0].passwordHash, PLACEHOLDER_PASSWORD_HASH);
    assert.match(PLACEHOLDER_PASSWORD_HASH, /^\$2b\$10\$.{53}$/);
    for (const candidate of ['AcmeHrManager2026!', 'password', '', 'changeme1234', PLACEHOLDER_PASSWORD_HASH]) {
        assert.equal(await bcrypt.compare(candidate, PLACEHOLDER_PASSWORD_HASH), false);
    }
});

test('FR-6.2: the employee and salary dataset does not depend on the HR password hash', () => {
    const withHash = generateDataset(100, { hrPasswordHash: hashHrPassword(TWELVE) });
    const without = generateDataset(100);
    assert.deepEqual(withHash.employees, without.employees);
    assert.deepEqual(withHash.salaries, without.salaries);
});

test('NFR-4: buildResetPasswordQuery binds the hash as a parameter and never embeds it in the SQL', () => {
    const hash = hashHrPassword(TWELVE);
    const { text, values } = buildResetPasswordQuery(hash);
    assert.match(text, /^UPDATE app_user SET password_hash = \$1 WHERE email = \$2$/);
    assert.ok(!text.includes(hash), 'the hash must not be concatenated into the SQL');
    assert.ok(!text.includes('$2a$') && !text.includes('$2b$'), 'no literal hash in the SQL');
    assert.deepEqual(values, [hash, 'hr.manager@acme.com']);
});

// The rate the API would use for a salary effective on `date`: the latest rate on or before it
// (requirements.md section 6.2 exchange_rate). USD -> USD is the identity. Written here, not taken
// from the seed, so the seed's own lookup is under test.
function expectedRate(currency, date) {
    if (currency === 'USD') return '1.00000000';
    const candidates = EXCHANGE_RATES
        .filter(r => r.from === currency && r.to === 'USD' && r.effectiveDate <= date)
        .sort((a, b) => (a.effectiveDate < b.effectiveDate ? 1 : -1));
    assert.ok(candidates.length > 0, `no ${currency}->USD rate on or before ${date}`);
    return candidates[0].rate;
}

test('NFR-2 / FR-3.4 / FR-3.5: every seeded salary record equals the exact recomputation from its base amount, frequency and effective-date rate', () => {
    const data = fullDataset();
    assert.ok(data.salaries.length > 0);
    for (const s of data.salaries) {
        const annualised = formatCents(annualise(parseCents(s.baseAmount), s.payFrequency));
        assert.equal(s.annualisedAmount, annualised, `record ${s.id}: annualised_amount`);

        const rate = expectedRate(s.currencyCode, s.effectiveFrom);
        assert.equal(s.annualisedAmountBaseCcy, oracleConvert(annualised, rate),
            `record ${s.id}: ${annualised} ${s.currencyCode} x ${rate} on ${s.effectiveFrom}`);
        assert.equal(s.annualisedAmountBaseCcy, formatCents(convertToBase(parseCents(annualised), parseRate(rate))));
    }
});

// FR-6.2: "the same seed yields the same dataset" has to hold on any machine. The seed parses dates
// as UTC, so formatting or stepping them with local-time getters/setters shifts a date by a day in
// zones behind UTC and makes the output depend on where it runs. Node re-reads process.env.TZ at
// runtime, so the zone can be switched inside one process.
test('FR-6.2: the dataset is identical whatever the machine time zone (Asia/Calcutta, America/Los_Angeles, Pacific/Auckland)', () => {
    const originalTz = process.env.TZ;
    const zones = ['Asia/Calcutta', 'America/Los_Angeles', 'Pacific/Auckland'];
    const results = [];
    try {
        for (const zone of zones) {
            process.env.TZ = zone;
            // Guard the premise: if the zone did not really change, this test would prove nothing.
            results.push({ zone, offset: new Date(2020, 0, 1).getTimezoneOffset(), data: generateDataset(300) });
        }
    } finally {
        if (originalTz === undefined) delete process.env.TZ; else process.env.TZ = originalTz;
    }
    assert.equal(new Set(results.map(r => r.offset)).size, zones.length, 'the time zone must really have changed');
    for (const r of results.slice(1)) {
        assert.deepEqual(r.data.employees, results[0].data.employees, `employees differ in ${r.zone} vs ${results[0].zone}`);
        assert.deepEqual(r.data.salaries, results[0].data.salaries, `salaries differ in ${r.zone} vs ${results[0].zone}`);
    }
});

// ---------------------------------------------------------------------------------------------
// FR-6.1: realistic pay distributions
// ---------------------------------------------------------------------------------------------

// The salary record in force today for each employee (the open-ended one, FR-3.6).
function currentSalaries(data) {
    return data.salaries.filter(s => s.effectiveTo === null);
}

const bandOf = (roleId, locationId) => PAY_BANDS.find(b => b.jobRoleId === roleId && b.locationId === locationId);

test('FR-6.1: pay bands apply a geographic multiplier to the USD level band before converting to local currency', () => {
    // Role 1 is an L1 role whose USD band is 70,000 / 85,000 / 100,000. Local = USD x location
    // multiplier x local-per-USD rate. Expected values worked out by hand:
    //   New York 1.00 (USD 1)        -> 70000 / 85000 / 100000
    //   San Francisco 1.10 (USD 1)   -> 77000 / 93500 / 110000
    //   London 0.85 x 0.78 GBP       -> 46410 / 56355 / 66300
    //   Berlin 0.80 x 0.92 EUR       -> 51520 / 62560 / 73600
    //   Bengaluru 0.35 x 83.5 INR    -> 2045750 / 2484125 / 2922500
    //   Singapore 0.75 x 1.34 SGD    -> 70350 / 85425 / 100500
    const expected = {
        1: ['70000.00', '85000.00', '100000.00'],
        2: ['77000.00', '93500.00', '110000.00'],
        3: ['46410.00', '56355.00', '66300.00'],
        4: ['51520.00', '62560.00', '73600.00'],
        5: ['2045750.00', '2484125.00', '2922500.00'],
        6: ['70350.00', '85425.00', '100500.00']
    };
    assert.equal(JOB_ROLES[0].level, 'L1');
    for (const [locationId, [min, mid, max]] of Object.entries(expected)) {
        const band = bandOf(JOB_ROLES[0].id, Number(locationId));
        assert.deepEqual([band.minAmount, band.midAmount, band.maxAmount], [min, mid, max], `location ${locationId}`);
    }
});

test('FR-6.1 / section 6.2 pay_band: every band satisfies min <= mid <= max and there is one per (role, location)', () => {
    const seen = new Set();
    for (const b of PAY_BANDS) {
        const [min, mid, max] = [parseCents(b.minAmount), parseCents(b.midAmount), parseCents(b.maxAmount)];
        assert.ok(min > 0n && min <= mid && mid <= max, `band ${b.id}: ${b.minAmount} / ${b.midAmount} / ${b.maxAmount}`);
        const key = `${b.jobRoleId}/${b.locationId}`;
        assert.ok(!seen.has(key), `duplicate band for ${key}`);
        seen.add(key);
    }
    assert.equal(seen.size, JOB_ROLES.length * LOCATIONS.length);
});

test('FR-6.1: findPayBand throws for a (role, location) that has no band instead of inventing a default', () => {
    assert.throws(() => findPayBand(99999, 1), /99999/);
    assert.equal(findPayBand(JOB_ROLES[0].id, 1), bandOf(JOB_ROLES[0].id, 1));
});

test('FR-6.1: every (job_role, location) pair used by an employee has a pay band in the employee currency', () => {
    const data = fullDataset();
    const locById = new Map(data.locations.map(l => [l.id, l]));
    const pairs = new Set();
    for (const e of data.employees) {
        const band = bandOf(e.jobRoleId, e.locationId);
        assert.ok(band, `no pay band for role ${e.jobRoleId} at location ${e.locationId}`);
        assert.equal(band.currencyCode, e.currencyCode);
        assert.equal(e.currencyCode, locById.get(e.locationId).currencyCode);
        pairs.add(`${e.jobRoleId}/${e.locationId}`);
    }
    assert.ok(pairs.size > 100, 'the dataset should exercise many different (role, location) pairs');
});

test('FR-3.5: every currency used has an exchange rate on or before every salary effective_from', () => {
    const data = fullDataset();
    for (const s of data.salaries) {
        expectedRate(s.currencyCode, s.effectiveFrom); // asserts a rate exists
    }
    const currencies = new Set(data.salaries.map(s => s.currencyCode));
    assert.deepEqual([...currencies].sort(), ['EUR', 'GBP', 'INR', 'SGD', 'USD']);
});

test('FR-6.1: geographic pay differs in USD terms (SF > NY > London; Bengaluru under half of New York)', () => {
    const data = fullDataset();
    const locOf = new Map(data.employees.map(e => [e.id, e.locationId]));
    const sum = new Map();
    const count = new Map();
    for (const s of currentSalaries(data)) {
        const loc = locOf.get(s.employeeId);
        sum.set(loc, (sum.get(loc) ?? 0n) + parseCents(s.annualisedAmountBaseCcy));
        count.set(loc, (count.get(loc) ?? 0n) + 1n);
    }
    const avg = (loc) => sum.get(loc) / count.get(loc);
    const [ny, sf, london, bengaluru] = [avg(1), avg(2), avg(3), avg(5)];
    assert.ok(sf > ny, `SF ${sf} should exceed NY ${ny}`);
    assert.ok(ny > london, `NY ${ny} should exceed London ${london}`);
    assert.ok(bengaluru * 2n < ny, `Bengaluru ${bengaluru} should be under half of NY ${ny}`);
});

test('FR-4.4 / FR-6.1: the pay-band report has people BELOW and ABOVE their band, but most WITHIN (loose ranges)', () => {
    const data = fullDataset();
    const empById = new Map(data.employees.map(e => [e.id, e]));
    let below = 0, within = 0, above = 0;
    for (const s of currentSalaries(data)) {
        const e = empById.get(s.employeeId);
        const band = bandOf(e.jobRoleId, e.locationId);
        const annual = parseCents(s.annualisedAmount);
        if (annual < parseCents(band.minAmount)) below++;
        else if (annual > parseCents(band.maxAmount)) above++;
        else within++;
    }
    const total = below + within + above;
    assert.equal(total, 10000, 'every employee has exactly one current record');
    // Target is roughly 8-15% each side; the asserted range is deliberately wider so the test only
    // fails if the mix collapses to (almost) nobody or (almost) everybody outside the band.
    assert.ok(below / total >= 0.05 && below / total <= 0.25, `below share ${below / total}`);
    assert.ok(above / total >= 0.05 && above / total <= 0.25, `above share ${above / total}`);
    assert.ok(within / total >= 0.5, `within share ${within / total}`);
});

test('FR-4.4: payBandAdherence counts below (< min), within (min..max inclusive) and above (> max) on current records only', () => {
    // Role 1 at New York has the band 70000.00 / 85000.00 / 100000.00.
    const employees = [1, 2, 3, 4, 5].map(id => ({ id, jobRoleId: 1, locationId: 1 }));
    const salaries = [
        { employeeId: 1, effectiveTo: null, annualisedAmount: '69999.99' },   // below
        { employeeId: 2, effectiveTo: null, annualisedAmount: '70000.00' },   // within (edge)
        { employeeId: 3, effectiveTo: null, annualisedAmount: '100000.00' },  // within (edge)
        { employeeId: 4, effectiveTo: null, annualisedAmount: '100000.01' },  // above
        { employeeId: 5, effectiveTo: '2024-01-01', annualisedAmount: '1.00' } // closed record: ignored
    ];
    assert.deepEqual(payBandAdherence({ employees, salaries }), { below: 1, within: 2, above: 1 });
});

test('NFR-1 / FR-6.1: 10,000 employees carry roughly 35,000 salary records (33,000 to 37,000)', () => {
    const n = fullDataset().salaries.length;
    assert.ok(n >= 33000 && n <= 37000, `expected 33,000-37,000 salary records, got ${n}`);
});

test('FR-3.4 / FR-6.1: pay frequency varies: about 30% of CONTRACT employees are HOURLY, about 10% of the rest MONTHLY', () => {
    const data = fullDataset();
    const freqByEmp = new Map();
    for (const s of data.salaries) {
        const seen = freqByEmp.get(s.employeeId);
        assert.ok(seen === undefined || seen === s.payFrequency, `employee ${s.employeeId} changes pay frequency between records`);
        freqByEmp.set(s.employeeId, s.payFrequency);
    }
    let contract = 0, contractHourly = 0, nonHourly = 0, monthly = 0, hourlyNonContract = 0;
    for (const e of data.employees) {
        const f = freqByEmp.get(e.id);
        if (e.employmentType === 'CONTRACT') {
            contract++;
            if (f === 'HOURLY') contractHourly++;
        } else if (f === 'HOURLY') {
            hourlyNonContract++;
        }
        if (f !== 'HOURLY') {
            nonHourly++;
            if (f === 'MONTHLY') monthly++;
        }
    }
    assert.equal(hourlyNonContract, 0, 'only CONTRACT employees are paid hourly');
    assert.ok(contract > 200, `expected several hundred CONTRACT employees, got ${contract}`);
    assert.ok(contractHourly / contract >= 0.2 && contractHourly / contract <= 0.4, `contract hourly share ${contractHourly / contract}`);
    assert.ok(monthly / nonHourly >= 0.05 && monthly / nonHourly <= 0.15, `monthly share of non-hourly ${monthly / nonHourly}`);
    assert.ok(data.salaries.some(s => s.payFrequency === 'ANNUAL'));
});

// ---------------------------------------------------------------------------------------------
// NFR-4: parameterised queries only. Data travels as bound values ($1..$n), never inside the SQL text.
// ---------------------------------------------------------------------------------------------

test('NFR-4: buildInsert numbers placeholders row by row and keeps values in column order', () => {
    const { text, values } = buildInsert('t', ['a', 'b'], [{ a: 1, b: 'x' }, { a: 2, b: 'y' }]);
    assert.equal(text, 'INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)');
    assert.deepEqual(values, [1, 'x', 2, 'y']);
});

test('NFR-4: buildInsert passes NULL (and undefined) as a null value, not as SQL text', () => {
    const { text, values } = buildInsert('t', ['a', 'b'], [{ a: null, b: undefined }]);
    assert.equal(text, 'INSERT INTO t (a, b) VALUES ($1, $2)');
    assert.deepEqual(values, [null, null]);
});

test('NFR-4: buildInsert keeps quotes, semicolons and SQL keywords in values, never in the text', () => {
    const nasty = "O'Brien'); DROP TABLE employee; --";
    const { text, values } = buildInsert('employee', ['last_name', 'note'], [{ last_name: nasty, note: '"quoted" \\ back' }]);
    assert.deepEqual(values, [nasty, '"quoted" \\ back']);
    assert.ok(!text.includes('Brien') && !text.includes('DROP') && !text.includes("'") && !text.includes('"'));
    assert.match(text, /^INSERT INTO employee \(last_name, note\) VALUES \(\$1, \$2\)$/);
});

test('NFR-4: buildInsert text contains only fixed identifiers and placeholders, whatever the data is', () => {
    const rows = Array.from({ length: 7 }, (_, i) => ({ id: i, name: `n${i}`, salary: '123456.78', gone: null }));
    const { text, values } = buildInsert('salary_record', ['id', 'name', 'salary', 'gone'], rows);
    assert.match(text, /^INSERT INTO salary_record \(id, name, salary, gone\) VALUES (\(\$\d+(, \$\d+){3}\)(, )?){7}$/);
    assert.ok(!text.includes('123456.78'));
    assert.equal(values.length, 28);
    assert.ok(text.endsWith('($25, $26, $27, $28)'));
});

test('NFR-4: buildInsert refuses table or column names that are not plain identifiers', () => {
    assert.throws(() => buildInsert('t; DROP TABLE x', ['a'], [{ a: 1 }]), /identifier/i);
    assert.throws(() => buildInsert('t', ['a", "b'], [{ 'a", "b': 1 }]), /identifier/i);
    assert.throws(() => buildInsert('t', ['A b'], [{ 'A b': 1 }]), /identifier/i);
});

test('NFR-4: buildInsert refuses no rows, no columns and more than 65,535 parameters', () => {
    assert.throws(() => buildInsert('t', ['a'], []), /rows/i);
    assert.throws(() => buildInsert('t', [], [{}]), /columns/i);
    const rows = Array.from({ length: 4000 }, (_, i) => ({ a: i, b: i, c: i, d: i, e: i, f: i, g: i, h: i, i: i, j: i, k: i, l: i, m: i, n: i, o: i, p: i, q: i }));
    assert.throws(() => buildInsert('t', Object.keys(rows[0]), rows), /65535/);
    assert.doesNotThrow(() => buildInsert('t', Object.keys(rows[0]), rows.slice(0, 3855)));
});

test('NFR-4: insertBatch sends parameterised statements of at most 1,000 rows, with data only in the values', async () => {
    const calls = [];
    const client = { query: async ({ text, values }) => { calls.push({ text, values }); } };
    const rows = Array.from({ length: 2500 }, (_, i) => ({ id: i + 1, name: `O'Neil ${i}` }));
    await insertBatch(client, 'employee', ['id', 'name'], rows, 1000);
    assert.equal(calls.length, 3);
    assert.deepEqual(calls.map(c => c.values.length), [2000, 2000, 1000]);
    for (const c of calls) {
        assert.ok(Array.isArray(c.values), 'values must be bound, not inlined');
        assert.ok(!c.text.includes("O'Neil") && !c.text.includes('Neil'), 'no data in the SQL text');
    }
    assert.deepEqual(calls[2].values.slice(0, 2), [2001, "O'Neil 2000"]);
});

test('NFR-4: insertBatch shrinks the batch so a wide table never exceeds 65,535 parameters', async () => {
    const calls = [];
    const client = { query: async ({ values }) => { calls.push(values.length); } };
    const columns = Array.from({ length: 40 }, (_, i) => `c${i}`);
    const rows = Array.from({ length: 3000 }, () => Object.fromEntries(columns.map(c => [c, 1])));
    await insertBatch(client, 't', columns, rows, 3000);
    assert.ok(calls.length > 1);
    assert.ok(calls.every(n => n <= 65535), `parameter counts ${calls}`);
    assert.equal(calls.reduce((a, b) => a + b, 0), 3000 * 40);
});

test('FR-6: every table spec provides a defined value for every column (no accidental NULLs) and inserts parents first', () => {
    const data = generateDataset(300);
    const specs = tableSpecs(data);
    assert.deepEqual(specs.map(s => s.table),
        ['app_user', 'department', 'location', 'job_role', 'pay_band', 'exchange_rate', 'employee', 'salary_record']);
    for (const spec of specs) {
        assert.ok(spec.rows.length > 0, spec.table);
        for (const row of spec.rows) {
            for (const col of spec.columns) {
                assert.notEqual(row[col], undefined, `${spec.table}.${col}`);
            }
        }
    }
    assert.equal(specs.find(s => s.table === 'employee').rows.length, 300);
    assert.equal(specs.find(s => s.table === 'salary_record').rows.length, data.salaries.length);
});

test('NFR-4: the --dump-sql script is a literal script for review, so renderInsertLiteral must escape quotes', () => {
    assert.equal(escapeSql("O'Brien"), "'O''Brien'");
    assert.equal(escapeSql(null), 'NULL');
    assert.equal(escapeSql(true), 'TRUE');
    assert.equal(escapeSql(5), '5');
    assert.equal(renderInsertLiteral('t', ['a', 'b'], [{ a: 1, b: "x'y" }, { a: null, b: 'z' }]),
        "INSERT INTO t (a, b) VALUES \n(1, 'x''y'),\n(NULL, 'z');");
});

test('NFR-4 / NFR-7: formatDbError prints only message, code and constraint, never the row in detail', () => {
    const err = Object.assign(new Error('duplicate key value violates unique constraint "employee_pkey"'), {
        code: '23505', constraint: 'employee_pkey', detail: 'Key (email)=(secret.person@acme.com) already exists.',
        where: 'COPY employee, line 1: salary 123456.78', stack: 'SECRET STACK'
    });
    const out = formatDbError(err);
    assert.match(out, /duplicate key value/);
    assert.match(out, /23505/);
    assert.match(out, /employee_pkey/);
    assert.ok(!out.includes('secret.person') && !out.includes('123456.78') && !out.includes('SECRET STACK'), out);
    assert.equal(formatDbError(new Error('boom')), 'boom');
});

// ---------------------------------------------------------------------------------------------
// --clean is destructive: TRUNCATE ... CASCADE also empties audit_log, import_job and import_error
// (audit data, NFR-7) and everything created through the UI. It needs an explicit --confirm-wipe.
// ---------------------------------------------------------------------------------------------

test('FR-6: WIPED_TABLES names every table --clean empties, including the audit and import tables', () => {
    assert.deepEqual([...WIPED_TABLES].sort(), [
        'app_user', 'audit_log', 'department', 'employee', 'exchange_rate', 'import_error',
        'import_job', 'job_role', 'location', 'pay_band', 'salary_record'
    ]);
});

test('FR-6: the TRUNCATE statement lists exactly the WIPED_TABLES', () => {
    const sql = buildTruncateStatement();
    assert.match(sql, /^TRUNCATE TABLE [a-z_, ]+ CASCADE$/);
    const listed = sql.replace(/^TRUNCATE TABLE /, '').replace(/ CASCADE$/, '').split(', ');
    assert.deepEqual(listed.sort(), [...WIPED_TABLES].sort());
});

test('FR-6: parseArgs with no arguments selects a normal seed and wipes nothing', () => {
    const r = parseArgs([]);
    assert.equal(r.ok, true);
    assert.deepEqual(r.options, { dryRun: false, dumpSql: false, clean: false, confirmWipe: false, resetHrPassword: false, help: false });
});

test('FR-6: parseArgs accepts --dry-run, --dump-sql, --reset-hr-password and --help', () => {
    assert.equal(parseArgs(['--dry-run']).options.dryRun, true);
    assert.equal(parseArgs(['--dump-sql']).options.dumpSql, true);
    assert.equal(parseArgs(['--reset-hr-password']).options.resetHrPassword, true);
    assert.equal(parseArgs(['--help']).options.help, true);
});

test('FR-6: parseArgs rejects --clean without --confirm-wipe and names every table that would be emptied', () => {
    const r = parseArgs(['--clean']);
    assert.equal(r.ok, false);
    assert.match(r.error, /--confirm-wipe/);
    for (const table of WIPED_TABLES) {
        assert.ok(r.error.includes(table), `error must name ${table}`);
    }
    assert.match(r.error, /audit/i);
});

test('FR-6: parseArgs rejects --clean without --confirm-wipe even next to other valid flags', () => {
    assert.equal(parseArgs(['--dry-run', '--clean']).ok, false);
});

test('FR-6: parseArgs accepts --clean together with --confirm-wipe, in either order', () => {
    for (const argv of [['--clean', '--confirm-wipe'], ['--confirm-wipe', '--clean']]) {
        const r = parseArgs(argv);
        assert.equal(r.ok, true);
        assert.equal(r.options.clean, true);
        assert.equal(r.options.confirmWipe, true);
    }
});

test('FR-6: --confirm-wipe on its own does nothing (it does not turn on --clean)', () => {
    const r = parseArgs(['--confirm-wipe']);
    assert.equal(r.ok, true);
    assert.equal(r.options.clean, false);
});

test('FR-6: parseArgs rejects unknown flags and stray positional arguments', () => {
    const unknown = parseArgs(['--claen']);
    assert.equal(unknown.ok, false);
    assert.match(unknown.error, /--claen/);
    assert.equal(parseArgs(['--dry-run', 'extra']).ok, false);
    assert.equal(parseArgs(['--clean=true']).ok, false);
});

test('FR-6: the usage text documents --confirm-wipe and warns that audit and import tables are wiped', () => {
    assert.match(USAGE, /--clean/);
    assert.match(USAGE, /--confirm-wipe/);
    assert.match(USAGE, /audit_log/);
    assert.match(USAGE, /import_job/);
    assert.match(USAGE, /import_error/);
});
