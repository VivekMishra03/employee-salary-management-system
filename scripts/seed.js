import pg from 'pg';
import dotenv from 'dotenv';
import bcrypt from 'bcryptjs';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Load .env from workspace root or current directory
const envPath = path.resolve(__dirname, '../.env');
if (fs.existsSync(envPath)) {
    dotenv.config({ path: envPath });
} else {
    dotenv.config();
}

// Fixed-seed PRNG (Mulberry32) for 100% reproducible, deterministic dataset (FR-6.2, NFR-3)
function createRng(seed = 42) {
    let s = seed >>> 0;
    return function() {
        s = (s + 0x6D2B79F5) >>> 0;
        let t = Math.imul(s ^ (s >>> 15), 1 | s);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

let currentRng = createRng(42);

function setSeed(seed = 42) {
    currentRng = createRng(seed);
}

const rng = () => currentRng();

function randChoice(arr) {
    return arr[Math.floor(currentRng() * arr.length)];
}

function randInt(min, max) {
    return Math.floor(currentRng() * (max - min + 1)) + min;
}

// Every date in the seed is a UTC instant (date-only ISO strings parse as UTC), so all reading and
// stepping uses the UTC accessors. Local-time accessors would make the dataset depend on the
// machine's time zone and break FR-6.2.

// Format Date to YYYY-MM-DD
function formatDate(d) {
    const year = d.getUTCFullYear();
    const month = String(d.getUTCMonth() + 1).padStart(2, '0');
    const day = String(d.getUTCDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function addDays(d, days) {
    const res = new Date(d.getTime());
    res.setUTCDate(res.getUTCDate() + days);
    return res;
}

function addMonths(d, months) {
    const res = new Date(d.getTime());
    res.setUTCMonth(res.getUTCMonth() + months);
    return res;
}

// -------------------------------------------------------------
// 0b. Exact money arithmetic (NFR-2)
// -------------------------------------------------------------
// Mirrors backend SalaryCalculator.java: amounts are BigInt cents, exchange rates are BigInt scaled
// by 1e8 (NUMERIC(18,8)), and every result is rounded HALF_UP exactly once. Floating point is never
// used for money, so a converted amount can never differ by a cent from what the API computes.

const MONEY_SCALE = 2;
const RATE_SCALE = 8;
const RATE_DENOMINATOR = 10n ** BigInt(RATE_SCALE);
// Percentages and multipliers are integers in basis points (1/100 of a percent): 10000 = 100 %.
const BPS = 10000n;
// requirements.md section 6.2: "monthly x 12, hourly x 2080".
const PERIODS_PER_YEAR = { ANNUAL: 1n, MONTHLY: 12n, HOURLY: 2080n };

// Integer division rounded HALF_UP (an exact half rounds away from zero), like BigDecimal HALF_UP.
function roundHalfUp(numerator, denominator) {
    if (denominator === 0n) {
        throw new Error('roundHalfUp: division by zero.');
    }
    const negative = (numerator < 0n) !== (denominator < 0n);
    const n = numerator < 0n ? -numerator : numerator;
    const d = denominator < 0n ? -denominator : denominator;
    const quotient = (2n * n + d) / (2n * d);
    return negative ? -quotient : quotient;
}

// Parses a non-negative decimal string such as '1234.50' into a BigInt scaled by 10^scale. More
// fractional digits than the scale allows is an error, never a silent rounding.
function parseScaled(text, scale, what) {
    const match = /^(\d+)(?:\.(\d+))?$/.exec(String(text));
    if (!match || (match[2] ?? '').length > scale) {
        throw new Error(`${what} '${text}' is not a non-negative decimal with at most ${scale} decimal places.`);
    }
    return BigInt(match[1] + (match[2] ?? '').padEnd(scale, '0'));
}

const parseCents = (text) => parseScaled(text, MONEY_SCALE, 'Amount');
const parseRate = (text) => parseScaled(text, RATE_SCALE, 'Exchange rate');

function formatScaled(value, scale) {
    const digits = (value < 0n ? -value : value).toString().padStart(scale + 1, '0');
    return `${value < 0n ? '-' : ''}${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
}

const formatCents = (cents) => formatScaled(cents, MONEY_SCALE);

// FR-3.4: the yearly equivalent of an amount paid at the given frequency. Cents x an integer is exact.
function annualise(baseCents, frequency) {
    const periods = PERIODS_PER_YEAR[frequency];
    if (periods === undefined) {
        throw new Error(`Unknown pay frequency '${frequency}'.`);
    }
    return baseCents * periods;
}

// FR-3.5: cents x (rate scaled by 1e8), rounded HALF_UP to a whole cent once, at full rate precision.
function convertToBase(cents, rateScaled) {
    if (rateScaled <= 0n) {
        throw new Error('Exchange rate must be positive.');
    }
    return roundHalfUp(cents * rateScaled, RATE_DENOMINATOR);
}

// -------------------------------------------------------------
// 1. Reference Data Definitions
// -------------------------------------------------------------

const DEPARTMENTS = [
    { id: 1, code: 'EXEC', name: 'Executive', parentId: null, costCenter: 'CC-001' },
    { id: 2, code: 'ENG', name: 'Engineering', parentId: null, costCenter: 'CC-100' },
    { id: 3, code: 'ENG-PLAT', name: 'Platform Engineering', parentId: 2, costCenter: 'CC-101' },
    { id: 4, code: 'PROD', name: 'Product', parentId: null, costCenter: 'CC-200' },
    { id: 5, code: 'SALES', name: 'Sales', parentId: null, costCenter: 'CC-300' },
    { id: 6, code: 'MKTG', name: 'Marketing', parentId: null, costCenter: 'CC-400' },
    { id: 7, code: 'PEOP', name: 'People & HR', parentId: null, costCenter: 'CC-500' },
    { id: 8, code: 'FIN', name: 'Finance', parentId: null, costCenter: 'CC-600' }
];

const LOCATIONS = [
    { id: 1, countryCode: 'US', countryName: 'United States', city: 'New York', currencyCode: 'USD' },
    { id: 2, countryCode: 'US', countryName: 'United States', city: 'San Francisco', currencyCode: 'USD' },
    { id: 3, countryCode: 'GB', countryName: 'United Kingdom', city: 'London', currencyCode: 'GBP' },
    { id: 4, countryCode: 'DE', countryName: 'Germany', city: 'Berlin', currencyCode: 'EUR' },
    { id: 5, countryCode: 'IN', countryName: 'India', city: 'Bengaluru', currencyCode: 'INR' },
    { id: 6, countryCode: 'SG', countryName: 'Singapore', city: 'Singapore', currencyCode: 'SGD' }
];

const FAMILIES = [
    {
        family: 'Engineering',
        titles: [
            'Associate Software Engineer', 'Software Engineer', 'Senior Software Engineer',
            'Staff Software Engineer', 'Principal Software Engineer', 'Director of Engineering',
            'VP of Engineering', 'Engineering Fellow'
        ]
    },
    {
        family: 'Product',
        titles: [
            'Associate Product Manager', 'Product Manager', 'Senior Product Manager',
            'Lead Product Manager', 'Principal Product Manager', 'Director of Product',
            'VP of Product', 'Chief Product Officer'
        ]
    },
    {
        family: 'Sales',
        titles: [
            'Sales Development Representative', 'Account Executive', 'Senior Account Executive',
            'Enterprise Account Executive', 'Sales Lead', 'Sales Director',
            'VP of Sales', 'Chief Commercial Officer'
        ]
    },
    {
        family: 'Marketing',
        titles: [
            'Marketing Associate', 'Marketing Specialist', 'Senior Marketing Specialist',
            'Marketing Lead', 'Senior Marketing Manager', 'Marketing Director',
            'VP of Marketing', 'Chief Marketing Officer'
        ]
    },
    {
        family: 'People',
        titles: [
            'People Coordinator', 'People Specialist', 'HR Business Partner',
            'Senior HR Business Partner', 'People Operations Manager', 'Director of People',
            'VP of People', 'Chief People Officer'
        ]
    },
    {
        family: 'Finance',
        titles: [
            'Financial Analyst', 'Senior Financial Analyst', 'Finance Manager',
            'Senior Finance Manager', 'Financial Controller', 'Director of Finance',
            'VP of Finance', 'Chief Financial Officer'
        ]
    }
];

const JOB_ROLES = [];
let roleIdSeq = 1;
for (const fam of FAMILIES) {
    for (let lvl = 1; lvl <= 8; lvl++) {
        JOB_ROLES.push({
            id: roleIdSeq++,
            title: fam.titles[lvl - 1],
            family: fam.family,
            level: `L${lvl}`
        });
    }
}

// Local currency units per 1 USD, scaled by 10^4. Used only to state pay bands in local currency; the
// dated exchange_rate table (below) is what converts salaries back to USD (FR-3.5).
const LOCAL_PER_USD_X10000 = {
    USD: 10000n,
    GBP: 7800n,
    EUR: 9200n,
    INR: 835000n,
    SGD: 13400n
};

// FR-6.1 geographic pay differential, in basis points of the USD level band (10000 = 1.00). Applied to
// the USD band *before* converting to the local currency, so it is a difference in USD terms.
const LOCATION_PAY_BPS = {
    'New York': 10000n,
    'San Francisco': 11000n,
    'London': 8500n,
    'Berlin': 8000n,
    'Singapore': 7500n,
    'Bengaluru': 3500n
};

// Share of CONTRACT employees paid hourly, and of all other non-hourly employees paid monthly (FR-3.4
// needs every frequency represented). Compared against rng() draws, never used for money.
const HOURLY_SHARE_OF_CONTRACT = 0.30;
const MONTHLY_SHARE = 0.10;
// Months between salary revisions; tuned so 10,000 employees carry about 35,000 records (NFR-1).
const REVISION_MONTHS_MIN = 12;
const REVISION_MONTHS_MAX = 15;
// Half-width of the compa-ratio spread around the band mid, in basis points (see salary generation).
const COMPA_SPREAD_BPS = 3200n;

// Base pay per level in whole USD (Min, Mid, Max)
const LEVEL_USD_BANDS = {
    L1: [70000n, 85000n, 100000n],
    L2: [95000n, 115000n, 135000n],
    L3: [130000n, 155000n, 180000n],
    L4: [170000n, 200000n, 230000n],
    L5: [220000n, 260000n, 300000n],
    L6: [280000n, 330000n, 380000n],
    L7: [360000n, 430000n, 500000n],
    L8: [480000n, 580000n, 680000n]
};

// USD level amount x location multiplier x local-per-USD rate, rounded HALF_UP to a whole currency
// unit (bands are clean numbers), returned as a money string.
function localBandAmount(usdAmount, location) {
    const locationBps = LOCATION_PAY_BPS[location.city];
    const localPerUsd = LOCAL_PER_USD_X10000[location.currencyCode];
    if (locationBps === undefined || localPerUsd === undefined) {
        throw new Error(`No pay multiplier or currency rate for ${location.city} (${location.currencyCode}).`);
    }
    const wholeUnits = roundHalfUp(usdAmount * locationBps * localPerUsd, BPS * BPS);
    return formatCents(wholeUnits * 100n);
}

// Generate Pay Bands for every (job_role × location)
const PAY_BANDS = [];
let payBandIdSeq = 1;
for (const role of JOB_ROLES) {
    for (const loc of LOCATIONS) {
        const [usdMin, usdMid, usdMax] = LEVEL_USD_BANDS[role.level];

        PAY_BANDS.push({
            id: payBandIdSeq++,
            jobRoleId: role.id,
            locationId: loc.id,
            currencyCode: loc.currencyCode,
            minAmount: localBandAmount(usdMin, loc),
            midAmount: localBandAmount(usdMid, loc),
            maxAmount: localBandAmount(usdMax, loc),
            effectiveFrom: '2020-01-01',
            effectiveTo: null
        });
    }
}

// Exchange rates table: historical rates from local currencies to USD
const EXCHANGE_RATES = [
    // GBP -> USD
    { id: 1, from: 'GBP', to: 'USD', rate: '1.30000000', effectiveDate: '2020-01-01', source: 'SEED_SYNTHETIC' },
    { id: 2, from: 'GBP', to: 'USD', rate: '1.35000000', effectiveDate: '2021-01-01', source: 'SEED_SYNTHETIC' },
    { id: 3, from: 'GBP', to: 'USD', rate: '1.32000000', effectiveDate: '2022-01-01', source: 'SEED_SYNTHETIC' },
    { id: 4, from: 'GBP', to: 'USD', rate: '1.24000000', effectiveDate: '2023-01-01', source: 'SEED_SYNTHETIC' },
    { id: 5, from: 'GBP', to: 'USD', rate: '1.27000000', effectiveDate: '2024-01-01', source: 'SEED_SYNTHETIC' },
    { id: 6, from: 'GBP', to: 'USD', rate: '1.28000000', effectiveDate: '2025-01-01', source: 'SEED_SYNTHETIC' },
    { id: 7, from: 'GBP', to: 'USD', rate: '1.29000000', effectiveDate: '2026-01-01', source: 'SEED_SYNTHETIC' },

    // EUR -> USD
    { id: 8, from: 'EUR', to: 'USD', rate: '1.12000000', effectiveDate: '2020-01-01', source: 'SEED_SYNTHETIC' },
    { id: 9, from: 'EUR', to: 'USD', rate: '1.21000000', effectiveDate: '2021-01-01', source: 'SEED_SYNTHETIC' },
    { id: 10, from: 'EUR', to: 'USD', rate: '1.13000000', effectiveDate: '2022-01-01', source: 'SEED_SYNTHETIC' },
    { id: 11, from: 'EUR', to: 'USD', rate: '1.08000000', effectiveDate: '2023-01-01', source: 'SEED_SYNTHETIC' },
    { id: 12, from: 'EUR', to: 'USD', rate: '1.09000000', effectiveDate: '2024-01-01', source: 'SEED_SYNTHETIC' },
    { id: 13, from: 'EUR', to: 'USD', rate: '1.07000000', effectiveDate: '2025-01-01', source: 'SEED_SYNTHETIC' },
    { id: 14, from: 'EUR', to: 'USD', rate: '1.08500000', effectiveDate: '2026-01-01', source: 'SEED_SYNTHETIC' },

    // INR -> USD
    { id: 15, from: 'INR', to: 'USD', rate: '0.01400000', effectiveDate: '2020-01-01', source: 'SEED_SYNTHETIC' },
    { id: 16, from: 'INR', to: 'USD', rate: '0.01370000', effectiveDate: '2021-01-01', source: 'SEED_SYNTHETIC' },
    { id: 17, from: 'INR', to: 'USD', rate: '0.01330000', effectiveDate: '2022-01-01', source: 'SEED_SYNTHETIC' },
    { id: 18, from: 'INR', to: 'USD', rate: '0.01210000', effectiveDate: '2023-01-01', source: 'SEED_SYNTHETIC' },
    { id: 19, from: 'INR', to: 'USD', rate: '0.01200000', effectiveDate: '2024-01-01', source: 'SEED_SYNTHETIC' },
    { id: 20, from: 'INR', to: 'USD', rate: '0.01180000', effectiveDate: '2025-01-01', source: 'SEED_SYNTHETIC' },
    { id: 21, from: 'INR', to: 'USD', rate: '0.01190000', effectiveDate: '2026-01-01', source: 'SEED_SYNTHETIC' },

    // SGD -> USD
    { id: 22, from: 'SGD', to: 'USD', rate: '0.74000000', effectiveDate: '2020-01-01', source: 'SEED_SYNTHETIC' },
    { id: 23, from: 'SGD', to: 'USD', rate: '0.75500000', effectiveDate: '2021-01-01', source: 'SEED_SYNTHETIC' },
    { id: 24, from: 'SGD', to: 'USD', rate: '0.74200000', effectiveDate: '2022-01-01', source: 'SEED_SYNTHETIC' },
    { id: 25, from: 'SGD', to: 'USD', rate: '0.74800000', effectiveDate: '2023-01-01', source: 'SEED_SYNTHETIC' },
    { id: 26, from: 'SGD', to: 'USD', rate: '0.74500000', effectiveDate: '2024-01-01', source: 'SEED_SYNTHETIC' },
    { id: 27, from: 'SGD', to: 'USD', rate: '0.75000000', effectiveDate: '2025-01-01', source: 'SEED_SYNTHETIC' },
    { id: 28, from: 'SGD', to: 'USD', rate: '0.75200000', effectiveDate: '2026-01-01', source: 'SEED_SYNTHETIC' }
];

// A missing band is an error, never a default amount: a made-up band would put wrong salaries in the
// seeded data without anyone noticing.
function findPayBand(jobRoleId, locationId) {
    const band = PAY_BANDS.find(b => b.jobRoleId === jobRoleId && b.locationId === locationId);
    if (!band) {
        throw new Error(`No pay band for job role ${jobRoleId} at location ${locationId}.`);
    }
    return band;
}

// FR-4.4 as the API reads it: each employee's current (open-ended) record against their band, using
// the annualised local amount; equal to a band edge is still within the band.
function payBandAdherence(data) {
    const employeeById = new Map(data.employees.map(e => [e.id, e]));
    const counts = { below: 0, within: 0, above: 0 };
    for (const s of data.salaries) {
        if (s.effectiveTo !== null) continue;
        const e = employeeById.get(s.employeeId);
        const band = findPayBand(e.jobRoleId, e.locationId);
        const annual = parseCents(s.annualisedAmount);
        if (annual < parseCents(band.minAmount)) counts.below++;
        else if (annual > parseCents(band.maxAmount)) counts.above++;
        else counts.within++;
    }
    return counts;
}

// FR-3.5: the latest rate on or before the date, as a BigInt scaled by 1e8. A missing rate is an
// error: silently falling back to another date's rate (or 1.0) would seed a wrong USD amount.
function getHistoricalFxRate(fromCcy, toCcy, dateStr) {
    if (fromCcy === toCcy) return RATE_DENOMINATOR;
    const rates = EXCHANGE_RATES.filter(r => r.from === fromCcy && r.to === toCcy && r.effectiveDate <= dateStr);
    if (rates.length === 0) {
        throw new Error(`No ${fromCcy}->${toCcy} exchange rate on or before ${dateStr}.`);
    }
    rates.sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate));
    return parseRate(rates[0].rate);
}

// -------------------------------------------------------------
// 2. Realistic Names and Attributes Pool
// -------------------------------------------------------------

const FIRST_NAMES_FEMALE = [
    'Emma', 'Olivia', 'Sophia', 'Ava', 'Isabella', 'Mia', 'Amelia', 'Harper', 'Evelyn', 'Abigail',
    'Emily', 'Ella', 'Elizabeth', 'Camila', 'Luna', 'Sofia', 'Avery', 'Mila', 'Aria', 'Scarlett',
    'Penelope', 'Layla', 'Chloe', 'Victoria', 'Madison', 'Eleanor', 'Grace', 'Nora', 'Riley', 'Zoey',
    'Priya', 'Ananya', 'Aarohi', 'Diya', 'Meera', 'Sneha', 'Deepika', 'Kavita', 'Fatima', 'Amina',
    'Yuki', 'Hana', 'Mei', 'Lin', 'Ying', 'Freja', 'Astrid', 'Clara', 'Leonie', 'Marie'
];

const FIRST_NAMES_MALE = [
    'Liam', 'Noah', 'Oliver', 'James', 'Elijah', 'William', 'Henry', 'Lucas', 'Benjamin', 'Theodore',
    'Jack', 'Levi', 'Alexander', 'Jackson', 'Mateo', 'Daniel', 'Michael', 'Mason', 'Sebastian', 'Ethan',
    'Logan', 'Owen', 'Samuel', 'Jacob', 'Asher', 'Aiden', 'John', 'Joseph', 'Wyatt', 'David',
    'Aarav', 'Rohan', 'Vihaan', 'Aditya', 'Arjun', 'Kabir', 'Vikram', 'Rajesh', 'Tariq', 'Zayn',
    'Kenji', 'Haruki', 'Wei', 'Chen', 'Bo', 'Lukas', 'Finn', 'Leon', 'Matteo', 'Paul'
];

const LAST_NAMES = [
    'Smith', 'Johnson', 'Williams', 'Brown', 'Jones', 'Garcia', 'Miller', 'Davis', 'Rodriguez', 'Martinez',
    'Hernandez', 'Lopez', 'Gonzalez', 'Wilson', 'Anderson', 'Thomas', 'Taylor', 'Moore', 'Jackson', 'Martin',
    'Lee', 'Perez', 'Thompson', 'White', 'Harris', 'Sanchez', 'Clark', 'Ramirez', 'Lewis', 'Robinson',
    'Walker', 'Young', 'Allen', 'King', 'Wright', 'Scott', 'Torres', 'Nguyen', 'Hill', 'Flores',
    'Green', 'Adams', 'Nelson', 'Baker', 'Hall', 'Rivera', 'Campbell', 'Mitchell', 'Carter', 'Roberts',
    'Sharma', 'Patel', 'Verma', 'Gupta', 'Rao', 'Reddy', 'Mishra', 'Nair', 'Iyer', 'Singh',
    'Müller', 'Schmidt', 'Schneider', 'Fischer', 'Weber', 'Meyer', 'Wagner', 'Becker', 'Schulz', 'Hoffmann',
    'Tanaka', 'Sato', 'Suzuki', 'Takahashi', 'Watanabe', 'Ito', 'Yamamoto', 'Nakamura', 'Kobayashi', 'Kato',
    'Tan', 'Lim', 'Ng', 'Ong', 'Wong', 'Goh', 'Chua', 'Koh', 'Teo', 'Ang'
];

// -------------------------------------------------------------
// 2b. HR Manager credential handling (NFR-4)
// -------------------------------------------------------------

const HR_EMAIL = 'hr.manager@acme.com';
const HR_PASSWORD_ENV = 'SEED_HR_PASSWORD';
const HR_PASSWORD_MIN_CHARS = 12;
// BCrypt only reads the first 72 bytes of its input; a longer password would be silently truncated.
const HR_PASSWORD_MAX_BYTES = 72;
const BCRYPT_COST = 10;

// Syntactically valid BCrypt string (7-char prefix + 53 chars) that no password can produce: it is
// used only where no real password is available (unit tests, --dry-run, --dump-sql). It is NOT a
// working credential, so an account created from it cannot be logged into.
const PLACEHOLDER_PASSWORD_HASH = '$2b$10$' + 'NOT.A.REAL.HASH.'.repeat(4).slice(0, 53);

// The HR password is supplied by the operator and is never committed or printed. Only the variable
// name appears in messages, never the value.
function requireHrPassword(env) {
    const password = env[HR_PASSWORD_ENV];
    if (typeof password !== 'string' || password.length === 0) {
        throw new Error(`${HR_PASSWORD_ENV} is not set. Choose a password of at least ${HR_PASSWORD_MIN_CHARS} characters.`);
    }
    if ([...password].length < HR_PASSWORD_MIN_CHARS) {
        throw new Error(`${HR_PASSWORD_ENV} must be at least ${HR_PASSWORD_MIN_CHARS} characters.`);
    }
    if (Buffer.byteLength(password, 'utf8') > HR_PASSWORD_MAX_BYTES) {
        throw new Error(`${HR_PASSWORD_ENV} must be at most ${HR_PASSWORD_MAX_BYTES} bytes (BCrypt ignores anything beyond that).`);
    }
    return password;
}

// BCrypt salts randomly, so hashing the same password twice gives two different (both valid) hashes.
// This is the one part of the seed that is not byte-for-byte reproducible between runs; the
// employee and salary dataset (FR-6.2) does not depend on it.
function hashHrPassword(password) {
    return bcrypt.hashSync(password, BCRYPT_COST);
}

// Single parameterised statement: the hash and email are bound values, never concatenated (NFR-4).
function buildResetPasswordQuery(passwordHash) {
    return {
        text: 'UPDATE app_user SET password_hash = $1 WHERE email = $2',
        values: [passwordHash, HR_EMAIL]
    };
}

// -------------------------------------------------------------
// 3. 10,000 Employees & ~35,000 Salaries Generator
// -------------------------------------------------------------

function generateDataset(totalEmployees = 10000, options = {}) {
    const seed = options.seed ?? 42;
    console.log(`Generating deterministic dataset for ${totalEmployees} employees (seed=${seed})...`);
    setSeed(seed);

    // 1. HR Manager app_user. The hash is supplied by the caller (from SEED_HR_PASSWORD); without one
    // a non-working placeholder is used so the dataset never carries a usable credential.
    const appUsers = [{
        id: 1,
        email: HR_EMAIL,
        passwordHash: options.hrPasswordHash ?? PLACEHOLDER_PASSWORD_HASH,
        fullName: 'HR Manager',
        role: 'HR_MANAGER',
        enabled: true,
        createdAt: '2026-09-20T00:00:00Z'
    }];

    // 2. Employees
    const employees = [];
    const salaries = [];
    let salaryIdSeq = 1;

    // We'll organize employees by job level so we can build a valid hierarchical manager DAG
    // L8 (Executives) -> L7 (VPs) -> L6 (Directors) -> L4/L5 (Leads/Principals) -> L1/L2/L3 (Individual Contributors)
    const employeesByLevel = {
        L1: [], L2: [], L3: [], L4: [], L5: [], L6: [], L7: [], L8: []
    };

    // Pre-calculate target level distribution for realistic org pyramid:
    // L1: ~18%, L2: ~28%, L3: ~24%, L4: ~14%, L5: ~8%, L6: ~5%, L7: ~2.5%, L8: ~0.5%
    const levelWeights = [
        { level: 'L1', weight: 0.18 },
        { level: 'L2', weight: 0.28 },
        { level: 'L3', weight: 0.24 },
        { level: 'L4', weight: 0.14 },
        { level: 'L5', weight: 0.08 },
        { level: 'L6', weight: 0.05 },
        { level: 'L7', weight: 0.025 },
        { level: 'L8', weight: 0.005 }
    ];

    function pickJobRole() {
        const r = rng();
        let cumulative = 0;
        let chosenLevel = 'L2';
        for (const lw of levelWeights) {
            cumulative += lw.weight;
            if (r <= cumulative) {
                chosenLevel = lw.level;
                break;
            }
        }
        const candidates = JOB_ROLES.filter(j => j.level === chosenLevel);
        return randChoice(candidates);
    }

    const usedEmails = new Set();

    for (let i = 1; i <= totalEmployees; i++) {
        const empCode = `ACME-${String(i).padStart(6, '0')}`;

        // Gender: ~45% FEMALE, ~45% MALE, ~5% NON_BINARY, ~5% PREFER_NOT_TO_SAY
        const gR = rng();
        let gender = 'FEMALE';
        let firstName = '';
        if (gR < 0.45) {
            gender = 'FEMALE';
            firstName = randChoice(FIRST_NAMES_FEMALE);
        } else if (gR < 0.90) {
            gender = 'MALE';
            firstName = randChoice(FIRST_NAMES_MALE);
        } else if (gR < 0.95) {
            gender = 'NON_BINARY';
            firstName = randChoice(FIRST_NAMES_FEMALE.concat(FIRST_NAMES_MALE));
        } else {
            gender = 'PREFER_NOT_TO_SAY';
            firstName = randChoice(FIRST_NAMES_FEMALE.concat(FIRST_NAMES_MALE));
        }

        const lastName = randChoice(LAST_NAMES);
        let baseEmail = `${firstName.toLowerCase().replace(/[^a-z]/g, '')}.${lastName.toLowerCase().replace(/[^a-z]/g, '')}`;
        let email = `${baseEmail}@acme.com`;
        if (usedEmails.has(email)) {
            email = `${baseEmail}.${i}@acme.com`;
        }
        usedEmails.add(email);

        // Hire Date: Between 2020-01-15 and 2026-06-30
        const startTimestamp = new Date('2020-01-15').getTime();
        const endTimestamp = new Date('2026-06-30').getTime();
        const hireTimestamp = startTimestamp + rng() * (endTimestamp - startTimestamp);
        const hireDate = new Date(hireTimestamp);
        const hireDateStr = formatDate(hireDate);

        // Employment Status: 92% ACTIVE, 3% ON_LEAVE, 5% TERMINATED
        const sR = rng();
        let employmentStatus = 'ACTIVE';
        let terminationDateStr = null;
        if (sR < 0.05) {
            employmentStatus = 'TERMINATED';
            // Termination date must be >= hire_date and <= 2026-09-20
            const maxDays = Math.max(30, Math.floor((new Date('2026-09-20').getTime() - hireTimestamp) / (86400000)));
            const daysToTerm = randInt(30, Math.max(30, maxDays));
            const termDate = addDays(hireDate, daysToTerm);
            terminationDateStr = formatDate(termDate);
        } else if (sR < 0.08) {
            employmentStatus = 'ON_LEAVE';
        }

        // Employment Type: 88% FULL_TIME, 8% PART_TIME, 4% CONTRACT
        const tR = rng();
        let employmentType = 'FULL_TIME';
        let fteRatio = '1.000';
        if (tR < 0.08) {
            employmentType = 'PART_TIME';
            fteRatio = randChoice(['0.500', '0.600', '0.700', '0.800']);
        } else if (tR < 0.12) {
            employmentType = 'CONTRACT';
            fteRatio = '1.000';
        }

        // Department: executive department reserved for L8, others distributed
        const jobRole = pickJobRole();
        let department = null;
        if (jobRole.level === 'L8' && rng() < 0.4) {
            department = DEPARTMENTS[0]; // EXEC
        } else {
            // Pick based on job family if possible
            const deptByFam = {
                Engineering: randChoice([DEPARTMENTS[1], DEPARTMENTS[2]]), // ENG, ENG-PLAT
                Product: DEPARTMENTS[3],
                Sales: DEPARTMENTS[4],
                Marketing: DEPARTMENTS[5],
                People: DEPARTMENTS[6],
                Finance: DEPARTMENTS[7]
            };
            department = deptByFam[jobRole.family] || randChoice(DEPARTMENTS.slice(1));
        }

        // Location:
        // US 50%, GB 15%, DE 15%, IN 15%, SG 5%
        const locR = rng();
        let location = LOCATIONS[0];
        if (locR < 0.25) location = LOCATIONS[0]; // US NY
        else if (locR < 0.50) location = LOCATIONS[1]; // US SF
        else if (locR < 0.65) location = LOCATIONS[2]; // UK London
        else if (locR < 0.80) location = LOCATIONS[3]; // DE Berlin
        else if (locR < 0.95) location = LOCATIONS[4]; // IN Bengaluru
        else location = LOCATIONS[5]; // SG Singapore

        const emp = {
            id: i,
            employeeCode: empCode,
            firstName,
            lastName,
            email,
            gender,
            hireDate: hireDateStr,
            terminationDate: terminationDateStr,
            employmentStatus,
            employmentType,
            fteRatio,
            departmentId: department.id,
            jobRoleId: jobRole.id,
            jobLevel: jobRole.level,
            locationId: location.id,
            currencyCode: location.currencyCode,
            managerId: null, // assigned in pass 2
            createdAt: `${hireDateStr}T09:00:00Z`,
            updatedAt: `${hireDateStr}T09:00:00Z`,
            version: 0
        };

        employees.push(emp);
        employeesByLevel[jobRole.level].push(emp);
    }

    // Pass 2: Assign manager hierarchy (L1-L3 -> L4/L5, L4/L5 -> L6, L6 -> L7, L7 -> L8, L8 -> CEO)
    // Employee #1 is designated CEO if L8, or top of L8
    const l8s = employeesByLevel.L8.length > 0 ? employeesByLevel.L8 : [employees[0]];
    const ceo = l8s[0];
    ceo.managerId = null;

    for (const emp of employees) {
        if (emp.id === ceo.id) continue;
        const lvl = emp.jobLevel;
        let managerPool = [];

        if (lvl === 'L8') {
            emp.managerId = ceo.id;
        } else if (lvl === 'L7') {
            managerPool = employeesByLevel.L8;
        } else if (lvl === 'L6') {
            managerPool = employeesByLevel.L7.length > 0 ? employeesByLevel.L7 : employeesByLevel.L8;
        } else if (lvl === 'L5' || lvl === 'L4') {
            managerPool = employeesByLevel.L6.length > 0 ? employeesByLevel.L6 : employeesByLevel.L7;
        } else {
            // L1, L2, L3
            managerPool = employeesByLevel.L4.concat(employeesByLevel.L5);
            if (managerPool.length === 0) managerPool = employeesByLevel.L6;
        }

        if (managerPool.length > 0) {
            // Prefer manager in same department
            const sameDeptManagers = managerPool.filter(m => m.departmentId === emp.departmentId && m.id !== emp.id);
            const chosenMgr = sameDeptManagers.length > 0 ? randChoice(sameDeptManagers) : randChoice(managerPool);
            emp.managerId = chosenMgr.id !== emp.id ? chosenMgr.id : ceo.id;
        } else {
            emp.managerId = ceo.id;
        }
    }

    // 3. Salary records: a history per employee (~3.5 records on average, NFR-1). All money below is
    // BigInt cents; percentages and multipliers are integer basis points (see the money section).
    const refNow = new Date('2026-09-20');
    for (const emp of employees) {
        const band = findPayBand(emp.jobRoleId, emp.locationId);
        const midCents = parseCents(band.midAmount);

        // Pay frequency is a property of the employment (constant across an employee's records).
        // Both draws are always taken so the random stream does not depend on the branch.
        const hourlyDraw = rng();
        const monthlyDraw = rng();
        let payFrequency = 'ANNUAL';
        if (emp.employmentType === 'CONTRACT' && hourlyDraw < HOURLY_SHARE_OF_CONTRACT) payFrequency = 'HOURLY';
        else if (monthlyDraw < MONTHLY_SHARE) payFrequency = 'MONTHLY';

        // Where today's pay sits against the band mid (compa-ratio), triangular (the sum of two
        // uniform draws), spread up to +-32 % around the mid. The band edges (about 0.83 and 1.17 of mid) then
        // catch roughly 10-14 % of employees on each side (FR-4.4 needs both).
        const spreadBps = randInt(0, 10000) + randInt(0, 10000) - 10000;
        const compaBps = BPS + roundHalfUp(BigInt(spreadBps) * COMPA_SPREAD_BPS, BPS);
        const currentAnnualTarget = roundHalfUp(midCents * compaBps, BPS);

        // Plan the dates first: a revision every 12-15 months until the termination date or today.
        const hireDate = new Date(emp.hireDate);
        const termDate = emp.terminationDate ? new Date(emp.terminationDate) : null;
        const horizon = termDate !== null && termDate < refNow ? termDate : refNow;
        const starts = [hireDate];
        for (let next = addMonths(hireDate, randInt(REVISION_MONTHS_MIN, REVISION_MONTHS_MAX)); next < horizon; next = addMonths(next, randInt(REVISION_MONTHS_MIN, REVISION_MONTHS_MAX))) {
            starts.push(next);
        }

        const reasons = ['NEW_HIRE'];
        const raiseBps = [];
        for (let i = 1; i < starts.length; i++) {
            const reason = randChoice(['MERIT_INCREASE', 'MERIT_INCREASE', 'PROMOTION', 'MARKET_ADJUSTMENT']);
            reasons.push(reason);
            raiseBps.push(BPS + BigInt(reason === 'PROMOTION' ? randInt(1000, 1800) : randInt(300, 800)));
        }

        // Pay is worked backwards from today's target so the *current* record lands where the compa
        // draw says, and earlier records are lower by the raises that followed them (pay history is
        // realistic, and bands stay meaningful without every long-serving employee drifting above them).
        let denominator = 1n;
        let scale = 1n;
        for (const r of raiseBps) {
            denominator *= r;
            scale *= BPS;
        }
        let annual = roundHalfUp(currentAnnualTarget * scale, denominator);

        for (let i = 0; i < starts.length; i++) {
            if (i > 0) {
                annual = roundHalfUp(annual * raiseBps[i - 1], BPS);
            }
            const fromStr = formatDate(starts[i]);
            const toStr = i < starts.length - 1 ? formatDate(starts[i + 1]) : null;

            // base_amount is stated in the pay frequency; the annualised amount is derived from it
            // exactly as SalaryCalculator does (x12 / x2080), then converted at the rate in force on
            // effective_from.
            const baseCents = roundHalfUp(annual, PERIODS_PER_YEAR[payFrequency]);
            const annualisedCents = annualise(baseCents, payFrequency);
            const convertedCents = convertToBase(annualisedCents, getHistoricalFxRate(emp.currencyCode, 'USD', fromStr));
            const reason = reasons[i];

            salaries.push({
                id: salaryIdSeq++,
                employeeId: emp.id,
                effectiveFrom: fromStr,
                effectiveTo: toStr,
                baseAmount: formatCents(baseCents),
                currencyCode: emp.currencyCode,
                payFrequency,
                annualisedAmount: formatCents(annualisedCents),
                annualisedAmountBaseCcy: formatCents(convertedCents),
                targetBonusPct: formatScaled(BigInt(randInt(500, 2000)), 2),
                changeReason: reason,
                notes: i === 0 ? 'Initial compensation package' : `${reason.replace('_', ' ')} adjustment`,
                createdBy: 1, // HR Manager
                createdAt: `${fromStr}T10:00:00Z`
            });
        }
    }


    console.log(`Generated:`);
    console.log(`- App Users: ${appUsers.length}`);
    console.log(`- Departments: ${DEPARTMENTS.length}`);
    console.log(`- Locations: ${LOCATIONS.length}`);
    console.log(`- Job Roles: ${JOB_ROLES.length}`);
    console.log(`- Pay Bands: ${PAY_BANDS.length}`);
    console.log(`- Exchange Rates: ${EXCHANGE_RATES.length}`);
    console.log(`- Employees: ${employees.length}`);
    console.log(`- Salary Records: ${salaries.length}`);
    const adherence = payBandAdherence({ employees, salaries });
    const share = (n) => `${((n * 100) / employees.length).toFixed(1)}%`; // display only, not money
    console.log(`- Pay band adherence (current records): below ${share(adherence.below)}, within ${share(adherence.within)}, above ${share(adherence.above)}`);

    return {
        appUsers,
        departments: DEPARTMENTS,
        locations: LOCATIONS,
        jobRoles: JOB_ROLES,
        payBands: PAY_BANDS,
        exchangeRates: EXCHANGE_RATES,
        employees,
        salaries
    };
}

// -------------------------------------------------------------
// 4. Batch SQL Generator & Direct Neon DB Injector
// -------------------------------------------------------------

// NFR-4: table and column names come only from the fixed constants in tableSpecs; the check below is
// defence in depth so a future edit can never turn an identifier into an injection point.
const SQL_IDENTIFIER = /^[a-z_][a-z0-9_]*$/;
// PostgreSQL's wire protocol carries the parameter count in 16 bits.
const MAX_QUERY_PARAMETERS = 65535;

function requireIdentifier(name) {
    if (typeof name !== 'string' || !SQL_IDENTIFIER.test(name)) {
        throw new Error(`Refusing to use '${name}' as a SQL identifier.`);
    }
    return name;
}

// A multi-row parameterised INSERT: the text holds only fixed identifiers and $n placeholders; every
// data value, quotes and all, travels in `values` in row-major, column order.
function buildInsert(table, columns, rows) {
    requireIdentifier(table);
    if (columns.length === 0) throw new Error('buildInsert needs a non-empty columns array.');
    columns.forEach(requireIdentifier);
    if (rows.length === 0) throw new Error('buildInsert needs a non-empty rows array.');
    if (rows.length * columns.length > MAX_QUERY_PARAMETERS) {
        throw new Error(`${rows.length} rows x ${columns.length} columns exceeds ${MAX_QUERY_PARAMETERS} query parameters.`);
    }
    const values = [];
    const tuples = rows.map(row => {
        const placeholders = columns.map(column => {
            values.push(row[column] === undefined ? null : row[column]);
            return `$${values.length}`;
        });
        return `(${placeholders.join(', ')})`;
    });
    return { text: `INSERT INTO ${table} (${columns.join(', ')}) VALUES ${tuples.join(', ')}`, values };
}

async function insertBatch(client, tableName, columns, rows, batchSize = 1000) {
    const size = Math.min(batchSize, Math.floor(MAX_QUERY_PARAMETERS / columns.length));
    for (let i = 0; i < rows.length; i += size) {
        await client.query(buildInsert(tableName, columns, rows.slice(i, i + size)));
    }
}

// --dump-sql only: the dump is a literal script written for a human to review and run, so it embeds
// escaped literals. The database load never uses this; it binds parameters (buildInsert).
function escapeSql(val) {
    if (val === null || val === undefined) return 'NULL';
    if (typeof val === 'number') return val.toString();
    if (typeof val === 'boolean') return val ? 'TRUE' : 'FALSE';
    return `'${String(val).replace(/'/g, "''")}'`;
}

function renderInsertLiteral(table, columns, rows) {
    const valuesSql = rows.map(row => `(${columns.map(c => escapeSql(row[c])).join(', ')})`).join(',\n');
    return `INSERT INTO ${table} (${columns.join(', ')}) VALUES \n${valuesSql};`;
}

// The tables the seed fills, in foreign-key order, with the column list and the rows for each. The
// database load and the SQL dump both read this one definition.
function tableSpecs(data) {
    return [
        {
            table: 'app_user',
            columns: ['id', 'email', 'password_hash', 'full_name', 'role', 'enabled', 'created_at'],
            rows: data.appUsers.map(u => ({ id: u.id, email: u.email, password_hash: u.passwordHash, full_name: u.fullName, role: u.role, enabled: u.enabled, created_at: u.createdAt }))
        },
        {
            table: 'department',
            columns: ['id', 'code', 'name', 'parent_department_id', 'cost_center'],
            rows: data.departments.map(d => ({ id: d.id, code: d.code, name: d.name, parent_department_id: d.parentId, cost_center: d.costCenter }))
        },
        {
            table: 'location',
            columns: ['id', 'country_code', 'country_name', 'city', 'currency_code'],
            rows: data.locations.map(l => ({ id: l.id, country_code: l.countryCode, country_name: l.countryName, city: l.city, currency_code: l.currencyCode }))
        },
        {
            table: 'job_role',
            columns: ['id', 'title', 'job_family', 'job_level'],
            rows: data.jobRoles.map(j => ({ id: j.id, title: j.title, job_family: j.family, job_level: j.level }))
        },
        {
            table: 'pay_band',
            columns: ['id', 'job_role_id', 'location_id', 'currency_code', 'min_amount', 'mid_amount', 'max_amount', 'effective_from', 'effective_to'],
            rows: data.payBands.map(p => ({ id: p.id, job_role_id: p.jobRoleId, location_id: p.locationId, currency_code: p.currencyCode, min_amount: p.minAmount, mid_amount: p.midAmount, max_amount: p.maxAmount, effective_from: p.effectiveFrom, effective_to: p.effectiveTo }))
        },
        {
            table: 'exchange_rate',
            columns: ['id', 'from_currency', 'to_currency', 'rate', 'effective_date', 'source'],
            rows: data.exchangeRates.map(e => ({ id: e.id, from_currency: e.from, to_currency: e.to, rate: e.rate, effective_date: e.effectiveDate, source: e.source }))
        },
        {
            table: 'employee',
            columns: ['id', 'employee_code', 'first_name', 'last_name', 'email', 'gender', 'hire_date', 'termination_date', 'employment_status', 'employment_type', 'fte_ratio', 'department_id', 'job_role_id', 'location_id', 'manager_id', 'created_at', 'updated_at', 'version'],
            rows: orderManagersFirst(data.employees).map(e => ({ id: e.id, employee_code: e.employeeCode, first_name: e.firstName, last_name: e.lastName, email: e.email, gender: e.gender, hire_date: e.hireDate, termination_date: e.terminationDate, employment_status: e.employmentStatus, employment_type: e.employmentType, fte_ratio: e.fteRatio, department_id: e.departmentId, job_role_id: e.jobRoleId, location_id: e.locationId, manager_id: e.managerId, created_at: e.createdAt, updated_at: e.updatedAt, version: e.version }))
        },
        {
            table: 'salary_record',
            columns: ['id', 'employee_id', 'effective_from', 'effective_to', 'base_amount', 'currency_code', 'pay_frequency', 'annualised_amount', 'annualised_amount_base_ccy', 'target_bonus_pct', 'change_reason', 'notes', 'created_by', 'created_at'],
            rows: data.salaries.map(s => ({ id: s.id, employee_id: s.employeeId, effective_from: s.effectiveFrom, effective_to: s.effectiveTo, base_amount: s.baseAmount, currency_code: s.currencyCode, pay_frequency: s.payFrequency, annualised_amount: s.annualisedAmount, annualised_amount_base_ccy: s.annualisedAmountBaseCcy, target_bonus_pct: s.targetBonusPct, change_reason: s.changeReason, notes: s.notes, created_by: s.createdBy, created_at: s.createdAt }))
        }
    ];
}

// A pg error can carry the failing row in `detail`/`where`, which for this data is PII and salary
// amounts (NFR-4). Only the message, SQLSTATE code and constraint name are ever printed.
function formatDbError(err) {
    const parts = [err.message];
    if (err.code) parts.push(`code=${err.code}`);
    if (err.constraint) parts.push(`constraint=${err.constraint}`);
    return parts.join(' ');
}

// --clean: everything the TRUNCATE ... CASCADE empties. audit_log, import_job and import_error are
// not seeded, but they reference app_user (directly or through import_job), so CASCADE empties them
// too -- they are listed explicitly so the statement, the refusal message and the docs cannot drift.
const WIPED_TABLES = [
    'salary_record', 'employee', 'pay_band', 'exchange_rate', 'job_role', 'location', 'department',
    'audit_log', 'import_error', 'import_job', 'app_user'
];

function buildTruncateStatement() {
    return `TRUNCATE TABLE ${WIPED_TABLES.map(requireIdentifier).join(', ')} CASCADE`;
}

const USAGE = `Usage: node scripts/seed.js [options]

Seeds 10,000 employees, their salary histories and reference data (FR-6).

Options:
  --dry-run              Generate and check the dataset; no database, no files
  --dump-sql             Write the dataset as a reviewable SQL script to seed.sql (repo root)
  --clean --confirm-wipe Re-seed a non-empty database. Both flags are required. This runs
                         TRUNCATE ... CASCADE and WIPES these tables:
                           ${WIPED_TABLES.join(', ')}
                         That includes audit_log, import_job and import_error and everything
                         created through the UI, not just seeded data.
  --reset-hr-password    Set the HR Manager password from SEED_HR_PASSWORD on an already-seeded database
  --help                 Show this text
`;

// Pure argument validation, run before anything reads the environment or contacts a database.
const KNOWN_FLAGS = {
    '--dry-run': 'dryRun',
    '--dump-sql': 'dumpSql',
    '--clean': 'clean',
    '--confirm-wipe': 'confirmWipe',
    '--reset-hr-password': 'resetHrPassword',
    '--help': 'help'
};

function parseArgs(argv) {
    const options = { dryRun: false, dumpSql: false, clean: false, confirmWipe: false, resetHrPassword: false, help: false };
    for (const arg of argv) {
        const key = KNOWN_FLAGS[arg];
        if (key === undefined) {
            return { ok: false, error: `Unknown argument '${arg}'.` };
        }
        options[key] = true;
    }
    if (options.clean && !options.confirmWipe) {
        return {
            ok: false,
            error: `--clean empties these tables: ${WIPED_TABLES.join(', ')}. That includes the audit log, import history ` +
                `and everything created through the UI, not just seeded data. Re-run with --clean --confirm-wipe to proceed.`
        };
    }
    return { ok: true, options };
}

// fk_employee_manager is immediate, not deferrable, so a manager row must exist before any row that
// references it. Ids follow creation order rather than hierarchy, so employees are sorted by depth in
// the reporting tree (top of the tree first), ties broken by id for a deterministic result (FR-6.2).
// Returns a new array; the input is left untouched.
function orderManagersFirst(employees) {
    const byId = new Map(employees.map(e => [e.id, e]));
    const depth = new Map();

    function depthOf(emp) {
        const path = [];
        let cur = emp;
        while (cur && !depth.has(cur.id)) {
            if (path.includes(cur.id)) {
                throw new Error(`Management cycle detected at employee ${cur.id}.`);
            }
            path.push(cur.id);
            if (cur.managerId === null) {
                break;
            }
            const next = byId.get(cur.managerId);
            if (!next) {
                throw new Error(`Manager ${cur.managerId} of employee ${cur.id} does not exist.`);
            }
            cur = next;
        }
        // Walk back down the path assigning depths from the known ancestor.
        let base = cur && depth.has(cur.id) ? depth.get(cur.id) : -1;
        for (let i = path.length - 1; i >= 0; i--) {
            if (depth.has(path[i])) {
                base = depth.get(path[i]);
            } else {
                base += 1;
                depth.set(path[i], base);
            }
        }
        return depth.get(emp.id);
    }

    // Depths are computed for everyone first: Array.sort may never call its comparator (a single
    // element), and validation of missing managers and cycles must not depend on that.
    employees.forEach(depthOf);
    return [...employees].sort((a, b) => depth.get(a.id) - depth.get(b.id) || a.id - b.id);
}

// FR-6 / NFR-6: connection settings come from the environment, credentials kept out of the URL
// (.env.example). Built from explicit fields rather than a connection string: with pg 8.23 a parsed
// connection string that carries no password overwrites an explicit one with null, which fails as
// "SASL: client password must be a string". TLS verifies the server certificate (ssl: true).
function buildClientConfig(env) {
    for (const name of ['DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD']) {
        if (!env[name]) {
            throw new Error(`${name} is not set.`);
        }
    }
    const url = new URL(env.DATABASE_URL.replace(/^jdbc:/, ''));
    return {
        host: url.hostname,
        port: url.port ? Number(url.port) : 5432,
        database: decodeURIComponent(url.pathname.replace(/^\//, '')),
        user: env.DATABASE_USERNAME,
        password: env.DATABASE_PASSWORD,
        ssl: true
    };
}

// Replaces the HR Manager's password hash on an already-seeded database. Touches app_user only and
// never creates the user: zero matched rows is a failure.
async function resetHrPassword() {
    let hash;
    let clientConfig;
    try {
        hash = hashHrPassword(requireHrPassword(process.env));
        clientConfig = buildClientConfig(process.env);
    } catch (err) {
        console.error(`ERROR: ${err.message} Provide it in .env or as an environment variable.`);
        process.exit(1);
    }

    const client = new pg.Client(clientConfig);
    let updated = 0;
    try {
        await client.connect();
        const res = await client.query(buildResetPasswordQuery(hash));
        updated = res.rowCount;
    } catch (err) {
        console.error('ERROR while resetting the HR password:', err.message);
        process.exitCode = 1;
        return;
    } finally {
        await client.end().catch(() => {});
    }

    if (updated !== 1) {
        console.error(`ERROR: expected to update exactly 1 row for ${HR_EMAIL}, updated ${updated}. No user was created.`);
        process.exitCode = 1;
        return;
    }
    console.log(`Password for ${HR_EMAIL} updated to the value of ${HR_PASSWORD_ENV}.`);
}

// Keeps each table's identity sequence ahead of the ids the seed inserted explicitly.
function sequenceSyncSql(table) {
    requireIdentifier(table);
    return `SELECT setval('${table}_id_seq', (SELECT MAX(id) FROM ${table}))`;
}

async function runSeed() {
    // Arguments are validated before the environment is read or any database is contacted.
    const parsed = parseArgs(process.argv.slice(2));
    if (!parsed.ok) {
        console.error(`ERROR: ${parsed.error}\n\n${USAGE}`);
        process.exit(1);
    }
    const options = parsed.options;
    if (options.help) {
        console.log(USAGE);
        return;
    }
    if (options.resetHrPassword) {
        await resetHrPassword();
        return;
    }

    const isDryRun = options.dryRun;
    const isDumpSql = options.dumpSql;
    const isClean = options.clean;

    const startTime = Date.now();

    // NFR-4: a real seed needs the HR password, checked before any database contact so a missing or
    // weak one fails fast. --dry-run and --dump-sql never carry a working hash.
    let hrPasswordHash;
    if (!isDryRun && !isDumpSql) {
        try {
            hrPasswordHash = hashHrPassword(requireHrPassword(process.env));
        } catch (err) {
            console.error(`ERROR: ${err.message}`);
            process.exit(1);
        }
    }

    const data = generateDataset(10000, { hrPasswordHash });

    if (isDryRun) {
        console.log(`[DRY-RUN] Verified dataset integrity cleanly in ${Date.now() - startTime}ms.`);
        process.exit(0);
    }

    if (isDumpSql) {
        const dumpPath = path.resolve(__dirname, '../seed.sql');
        console.log(`Dumping SQL to ${dumpPath}...`);
        const stream = fs.createWriteStream(dumpPath, { encoding: 'utf8' });

        stream.write('-- ACME Employee Salary Management System - FR-6 Seed SQL\n');
        stream.write('-- This is a script for a human to review and run, so it contains escaped SQL literals. The\n');
        stream.write('-- database load done by `node scripts/seed.js` itself uses parameterised queries only (NFR-4).\n');
        stream.write('-- NOTE: the app_user password_hash below is a NON-WORKING placeholder (NFR-4). Set a real\n');
        stream.write('-- password afterwards with: node scripts/seed.js --reset-hr-password (needs SEED_HR_PASSWORD).\n');
        stream.write('BEGIN;\n\n');

        const specs = tableSpecs(data);
        for (const { table, columns, rows } of specs) {
            stream.write(`-- ${table} (${rows.length} rows)\n`);
            for (let i = 0; i < rows.length; i += 500) {
                stream.write(`${renderInsertLiteral(table, columns, rows.slice(i, i + 500))}\n\n`);
            }
        }

        stream.write('-- Sequence synchronization\n');
        for (const { table } of specs) {
            stream.write(`${sequenceSyncSql(table)};\n`);
        }
        stream.write('\nCOMMIT;\n');

        stream.end();
        await new Promise((resolve) => stream.on('finish', resolve));
        console.log(`Dumped successfully to ${dumpPath} in ${Date.now() - startTime}ms.`);
        process.exit(0);
    }

    // Connect to PostgreSQL
    let clientConfig;
    try {
        clientConfig = buildClientConfig(process.env);
    } catch (err) {
        console.error(`ERROR: ${err.message} Provide it in .env or as an environment variable.`);
        process.exit(1);
    }

    const client = new pg.Client(clientConfig);

    try {
        console.log('Connecting to database...');
        await client.connect();
        console.log('Connected successfully.');

        // Check if employees already exist
        const checkRes = await client.query('SELECT COUNT(*) FROM employee;');
        const currentCount = parseInt(checkRes.rows[0].count, 10);

        if (currentCount > 0 && !isClean) {
            console.log(`Database already has ${currentCount} employees. Use --clean --confirm-wipe to re-seed (this empties ${WIPED_TABLES.join(', ')}).`);
            await client.end();
            process.exit(0);
        }

        console.log('Beginning database transaction...');
        await client.query('BEGIN;');

        if (isClean && currentCount > 0) {
            console.log(`Cleaning existing records (TRUNCATE ${WIPED_TABLES.join(', ')})...`);
            await client.query(buildTruncateStatement());
        }

        const specs = tableSpecs(data);
        for (const { table, columns, rows } of specs) {
            console.log(`Inserting ${rows.length} rows into ${table}...`);
            await insertBatch(client, table, columns, rows, 1000);
        }

        console.log('Synchronizing PostgreSQL sequences...');
        for (const { table } of specs) {
            await client.query(sequenceSyncSql(table));
        }

        await client.query('COMMIT;');
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(2);
        console.log(`\n======================================================`);
        console.log(`SUCCESS: Seeding completed in ${elapsed}s!`);
        console.log(`- 1 App User (${HR_EMAIL}; password is the value of ${HR_PASSWORD_ENV})`);
        console.log(`- ${data.departments.length} Departments`);
        console.log(`- ${data.locations.length} Locations`);
        console.log(`- ${data.jobRoles.length} Job Roles`);
        console.log(`- ${data.payBands.length} Pay Bands`);
        console.log(`- ${data.exchangeRates.length} Exchange Rates`);
        console.log(`- ${data.employees.length} Employees`);
        console.log(`- ${data.salaries.length} Salary Records`);
        console.log(`======================================================\n`);

    } catch (err) {
        // Never print the raw error object: a pg error can carry the failing row (PII, salary).
        console.error('ERROR during seeding, rolling back transaction:', formatDbError(err));
        try {
            await client.query('ROLLBACK;');
        } catch (_) {}
        process.exit(1);
    } finally {
        await client.end();
    }
}

export {
    parseCents, parseRate, formatCents, roundHalfUp, annualise, convertToBase, findPayBand, payBandAdherence,
    buildInsert, insertBatch, tableSpecs, escapeSql, renderInsertLiteral, formatDbError,
    parseArgs, WIPED_TABLES, buildTruncateStatement, USAGE,
    generateDataset, buildClientConfig, orderManagersFirst, createRng, hashHrPassword, requireHrPassword,
    buildResetPasswordQuery, PLACEHOLDER_PASSWORD_HASH, DEPARTMENTS, LOCATIONS, JOB_ROLES, PAY_BANDS, EXCHANGE_RATES
};

if (process.argv[1] && (process.argv[1].endsWith('seed.js') || process.argv[1].endsWith('seed'))) {
    runSeed();
}
