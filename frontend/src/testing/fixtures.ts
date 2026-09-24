// Shared test data. Shapes mirror the backend DTOs; values are arbitrary but fixed so a test that
// asserts on them reads as a sentence. Only spec files import this (tsconfig.app.json excludes it).
import { EmployeeDetail, EmployeeListItem, Page, SalarySummary } from '../app/core/models/employee.model';
import { Department, JobRole, Location } from '../app/core/models/reference.model';
import {
  AnalyticsSummary, DistributionBucket, GenderGapStat, GroupStat, PayBandEmployee, PayBandReport, TrendPoint,
} from '../app/core/models/analytics.model';

export const DEPARTMENTS: Department[] = [
  { id: 1, code: 'ENG', name: 'Engineering', parentDepartmentId: null },
  { id: 2, code: 'SALES', name: 'Sales', parentDepartmentId: null },
];

export const LOCATIONS: Location[] = [
  { id: 1, countryCode: 'DE', countryName: 'Germany', city: 'Berlin', currencyCode: 'EUR' },
  { id: 2, countryCode: 'DE', countryName: 'Germany', city: 'Munich', currencyCode: 'EUR' },
  { id: 3, countryCode: 'US', countryName: 'United States', city: 'Austin', currencyCode: 'USD' },
];

export const JOB_ROLES: JobRole[] = [
  { id: 1, title: 'Software Engineer', jobFamily: 'Engineering', jobLevel: 'L3' },
  { id: 2, title: 'Senior Software Engineer', jobFamily: 'Engineering', jobLevel: 'L4' },
  { id: 3, title: 'Account Executive', jobFamily: 'Sales', jobLevel: 'L3' },
];

export function listItem(overrides: Partial<EmployeeListItem> = {}): EmployeeListItem {
  return {
    id: 42,
    employeeCode: 'E00042',
    firstName: 'Ada',
    lastName: 'Byron',
    email: 'ada.byron@acme.com',
    employmentStatus: 'ACTIVE',
    employmentType: 'FULL_TIME',
    hireDate: '2021-03-01',
    departmentId: 1,
    departmentName: 'Engineering',
    jobRoleId: 2,
    jobTitle: 'Senior Software Engineer',
    jobLevel: 'L4',
    locationId: 1,
    city: 'Berlin',
    countryCode: 'DE',
    ...overrides,
  };
}

export function page<T>(content: T[], totalElements = content.length, pageNumber = 0, size = 25): Page<T> {
  return { content, page: pageNumber, size, totalElements, totalPages: Math.ceil(totalElements / size) };
}

export function salary(overrides: Partial<SalarySummary> = {}): SalarySummary {
  return {
    id: 7,
    effectiveFrom: '2024-01-01',
    effectiveTo: null,
    baseAmount: 95000,
    currencyCode: 'EUR',
    payFrequency: 'ANNUAL',
    annualisedAmount: 95000,
    annualisedAmountBaseCcy: 103550.5,
    targetBonusPct: 10,
    changeReason: 'MERIT_INCREASE',
    notes: null,
    ...overrides,
  };
}

/** Ada: three records, the middle one superseded (zero-length, ADR-0011) by a same-day correction. */
export function detail(overrides: Partial<EmployeeDetail> = {}): EmployeeDetail {
  const current = salary({ id: 9, effectiveFrom: '2024-01-01', effectiveTo: null });
  const superseded = salary({ id: 8, effectiveFrom: '2024-01-01', effectiveTo: '2024-01-01', baseAmount: 90000, changeReason: 'CORRECTION' });
  const first = salary({ id: 7, effectiveFrom: '2021-03-01', effectiveTo: '2024-01-01', baseAmount: 80000, annualisedAmount: 80000, annualisedAmountBaseCcy: 87200, changeReason: 'NEW_HIRE' });
  return {
    id: 42,
    employeeCode: 'E00042',
    firstName: 'Ada',
    lastName: 'Byron',
    email: 'ada.byron@acme.com',
    gender: 'FEMALE',
    hireDate: '2021-03-01',
    terminationDate: null,
    employmentStatus: 'ACTIVE',
    employmentType: 'FULL_TIME',
    fteRatio: 1,
    departmentId: 1,
    departmentName: 'Engineering',
    jobRoleId: 2,
    jobTitle: 'Senior Software Engineer',
    jobLevel: 'L4',
    locationId: 1,
    city: 'Berlin',
    countryCode: 'DE',
    manager: { id: 7, employeeCode: 'E00007', fullName: 'Grace Hopper' },
    directReports: [
      { id: 101, employeeCode: 'E00101', fullName: 'Linus Benedict' },
      { id: 102, employeeCode: 'E00102', fullName: 'Margaret Hamilton' },
    ],
    currentSalary: current,
    salaryHistory: [current, superseded, first],
    version: 3,
    createdAt: '2021-03-01T09:00:00Z',
    updatedAt: '2024-01-01T09:00:00Z',
    ...overrides,
  };
}

// FR-4: analytics response shapes (backend dto/*.java). Figures are arbitrary but fixed.
export function summaryStats(overrides: Partial<AnalyticsSummary> = {}): AnalyticsSummary {
  return {
    headcount: 3, totalPayroll: 300000.5, mean: 100000.17, median: 99000, p25: 90000.25, p75: 110000,
    ratesAsOf: '2026-09-01', ...overrides,
  };
}

export const EMPTY_SUMMARY: AnalyticsSummary = {
  headcount: 0, totalPayroll: 0, mean: null, median: null, p25: null, p75: null, ratesAsOf: '2026-09-01',
};

export function groupStats(): GroupStat[] {
  return [
    { key: '1', label: 'Engineering', headcount: 40, median: 120000, mean: 125000.5 },
    { key: '2', label: 'Sales', headcount: 25, median: 80000, mean: 82000 },
  ];
}

export function buckets(): DistributionBucket[] {
  return [
    { lower: 50000, upper: 75000, count: 12 },
    { lower: 75000, upper: 100000, count: 30 },
    { lower: 100000, upper: 125000, count: 8 },
  ];
}

/** One measurable group and one suppressed one; the suppressed one carries nulls, as the API sends it (ADR-0013). */
export function genderGaps(): GenderGapStat[] {
  return [
    { key: '1', label: 'Engineering', maleCount: 30, femaleCount: 20, meanGapPct: 4.25, medianGapPct: -3.1, suppressed: false },
    { key: '2', label: 'Legal', maleCount: null, femaleCount: null, meanGapPct: null, medianGapPct: null, suppressed: true },
  ];
}

export function payBandEmployee(overrides: Partial<PayBandEmployee> = {}): PayBandEmployee {
  return {
    employeeId: 42, employeeCode: 'E00042', fullName: 'Ada Byron', jobTitle: 'Senior Software Engineer', jobLevel: 'L4',
    countryCode: 'DE', currencyCode: 'EUR', annualisedAmount: 95000, bandMin: 80000, bandMid: 100000, bandMax: 120000,
    compaRatio: 0.95, adherence: 'WITHIN', ...overrides,
  };
}

export function payBandReport(content: PayBandEmployee[] = [payBandEmployee()], totalElements = content.length): PayBandReport {
  return {
    counts: { below: 4, within: 30, above: 2, noBand: 1 },
    employees: { content, page: 0, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) },
  };
}

export function trendPoints(): TrendPoint[] {
  return [
    { period: '2026-01', totalPayrollUsd: 1000000, avgIncreasePct: null },
    { period: '2026-02', totalPayrollUsd: 1050000.5, avgIncreasePct: 3.5 },
    { period: '2026-03', totalPayrollUsd: 1100000, avgIncreasePct: 2.25 },
  ];
}
