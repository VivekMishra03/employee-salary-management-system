// FR-2.2, FR-2.5, FR-3.1: TypeScript mirrors of the backend DTOs in backend/src/main/java/com/acme/salary/dto.
// Field names and nullability are copied from those records, not designed here.

export type EmploymentStatus = 'ACTIVE' | 'ON_LEAVE' | 'TERMINATED';
export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT';
export type PayFrequency = 'ANNUAL' | 'MONTHLY' | 'HOURLY';
export type ChangeReason =
  | 'NEW_HIRE' | 'MERIT_INCREASE' | 'PROMOTION' | 'MARKET_ADJUSTMENT' | 'ROLE_CHANGE' | 'DEMOTION' | 'CORRECTION';
export type Gender = 'FEMALE' | 'MALE' | 'NON_BINARY' | 'PREFER_NOT_TO_SAY';

export const EMPLOYMENT_TYPES: readonly EmploymentType[] = ['FULL_TIME', 'PART_TIME', 'CONTRACT'];
export const PAY_FREQUENCIES: readonly PayFrequency[] = ['ANNUAL', 'MONTHLY', 'HOURLY'];
export const CHANGE_REASONS: readonly ChangeReason[] =
  ['NEW_HIRE', 'MERIT_INCREASE', 'PROMOTION', 'MARKET_ADJUSTMENT', 'ROLE_CHANGE', 'DEMOTION', 'CORRECTION'];
export const GENDERS: readonly Gender[] = ['FEMALE', 'MALE', 'NON_BINARY', 'PREFER_NOT_TO_SAY'];

/** FR-2.2: one directory row (EmployeeListItem.java). No gender, by requirements.md assumption 3. */
export interface EmployeeListItem {
  id: number;
  employeeCode: string;
  firstName: string;
  lastName: string;
  email: string;
  employmentStatus: EmploymentStatus;
  employmentType: EmploymentType;
  hireDate: string; // ISO date
  departmentId: number;
  departmentName: string;
  jobRoleId: number;
  jobTitle: string;
  jobLevel: string;
  locationId: number;
  city: string;
  countryCode: string;
}

/** FR-2.5: the full employee view (EmployeeDetail.java). */
export interface EmployeeDetail {
  id: number;
  employeeCode: string;
  firstName: string;
  lastName: string;
  email: string;
  gender: Gender | null;
  hireDate: string;
  terminationDate: string | null;
  employmentStatus: EmploymentStatus;
  employmentType: EmploymentType;
  fteRatio: number;
  departmentId: number;
  departmentName: string;
  jobRoleId: number;
  jobTitle: string;
  jobLevel: string;
  locationId: number;
  city: string;
  countryCode: string;
  manager: PersonRef | null;
  directReports: PersonRef[];
  currentSalary: SalarySummary | null;
  salaryHistory: SalarySummary[];
  version: number; // FR-2.6
  createdAt: string;
  updatedAt: string;
}

export interface PersonRef {
  id: number;
  employeeCode: string;
  fullName: string;
}

/**
 * FR-3.1: one salary record (EmployeeDetail.SalarySummary.java). Amounts are NUMERIC(15,2) on the
 * wire; the UI formats them (MoneyPipe) and never does arithmetic on them (NFR-2).
 */
export interface SalarySummary {
  id: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  baseAmount: number;
  currencyCode: string;
  payFrequency: PayFrequency;
  annualisedAmount: number;
  annualisedAmountBaseCcy: number;
  targetBonusPct: number;
  changeReason: ChangeReason;
  notes: string | null;
}

/** FR-2.2: the page envelope (PageResponse.java). `page` is zero-based. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** FR-3.2 – FR-3.6 (RecordSalaryRequest.java). No currency: the server takes it from the location (FR-3.4). */
export interface RecordSalaryRequest {
  effectiveFrom: string;
  baseAmount: number;
  payFrequency: PayFrequency;
  targetBonusPct: number;
  changeReason: ChangeReason;
  notes: string | null;
}

/** FR-2.1 (CreateEmployeeRequest.java). A new employee is always ACTIVE, so there is no status field. */
export interface CreateEmployeeRequest {
  employeeCode: string;
  firstName: string;
  lastName: string;
  email: string;
  gender: Gender | null;
  hireDate: string;
  employmentType: EmploymentType;
  fteRatio: number;
  departmentId: number;
  jobRoleId: number;
  locationId: number;
  managerId: number | null;
}

/**
 * FR-2.1 / FR-2.6 (UpdateEmployeeRequest.java). `employeeCode` and `hireDate` are immutable and
 * absent on purpose (ADR-0010); `version` is mandatory so a blind overwrite is impossible.
 */
export interface UpdateEmployeeRequest {
  version: number;
  firstName: string;
  lastName: string;
  email: string;
  gender: Gender | null;
  employmentType: EmploymentType;
  fteRatio: number;
  departmentId: number;
  jobRoleId: number;
  locationId: number;
  managerId: number | null;
  employmentStatus: EmploymentStatus;
}
