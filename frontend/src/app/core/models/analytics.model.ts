// FR-4.1 - FR-4.7: TypeScript mirrors of the analytics DTOs in backend/src/main/java/com/acme/salary/dto.
// Field names and nullability are copied from those records (BigDecimal is a JSON number). Nothing
// here is designed; where a Java field can be null it is `| null` here even if it usually is not.
import { EmploymentStatus, EmploymentType } from './employee.model';

/** FR-4.7: the same parameters as GET /employees (EmployeeFilter.java). Absent or empty means "no constraint". */
export interface AnalyticsFilter {
  q?: string;
  departmentId?: number;
  countryCode?: string;
  status?: EmploymentStatus;
  employmentType?: EmploymentType;
  jobLevel?: string;
}

export type GroupBy = 'DEPARTMENT' | 'COUNTRY' | 'JOB_LEVEL';
/** FR-4.5 offers department and job level only; the API refuses COUNTRY with UNSUPPORTED_GROUP_BY. */
export type GenderGapGroupBy = 'DEPARTMENT' | 'JOB_LEVEL';
export type TrendInterval = 'MONTH' | 'QUARTER' | 'YEAR';
export type PayBandAdherence = 'BELOW' | 'WITHIN' | 'ABOVE' | 'NO_BAND';

/**
 * FR-4.1 (AnalyticsSummary.java): for an empty slice headcount and payroll are 0 and the statistics null.
 * `ratesAsOf` (requirements Assumption 2) is the latest effective date in the exchange-rate table,
 * `yyyy-MM-dd`; it describes the rates, not the slice, so it is present for an empty slice too, and null
 * only when there are no rates at all.
 */
export interface AnalyticsSummary {
  headcount: number;
  totalPayroll: number;
  mean: number | null;
  median: number | null;
  p25: number | null;
  p75: number | null;
  ratesAsOf: string | null;
}

/** FR-4.2 (GroupStat.java). */
export interface GroupStat {
  key: string;
  label: string;
  headcount: number;
  median: number | null;
  mean: number | null;
}

/** FR-4.3 (DistributionBucket.java): [lower, upper) except the last bucket, which includes upper. */
export interface DistributionBucket {
  lower: number;
  upper: number;
  count: number;
}

/** FR-4.5 (GenderGapStat.java): positive = women paid less; suppressed groups carry no counts and no gaps. */
export interface GenderGapStat {
  key: string;
  label: string;
  maleCount: number | null;
  femaleCount: number | null;
  meanGapPct: number | null;
  medianGapPct: number | null;
  suppressed: boolean;
}

/** FR-4.4 (PayBandCounts.java). */
export interface PayBandCounts {
  below: number;
  within: number;
  above: number;
  noBand: number;
}

/** FR-4.4 (PayBandEmployee.java): LOCAL currency; band fields and compaRatio are null with no band. */
export interface PayBandEmployee {
  employeeId: number;
  employeeCode: string;
  fullName: string;
  jobTitle: string;
  jobLevel: string;
  countryCode: string;
  currencyCode: string;
  annualisedAmount: number;
  bandMin: number | null;
  bandMid: number | null;
  bandMax: number | null;
  compaRatio: number | null;
  adherence: PayBandAdherence;
}

/** FR-4.4 (PayBandReport.java): counts for the whole slice plus one server page of employees. */
export interface PayBandReport {
  counts: PayBandCounts;
  employees: {
    content: PayBandEmployee[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
}

/** FR-4.6 (TrendPoint.java): period is 2026-03, 2026-Q1 or 2026. */
export interface TrendPoint {
  period: string;
  totalPayrollUsd: number;
  avgIncreasePct: number | null;
}
