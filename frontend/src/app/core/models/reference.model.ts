// Lookup lists behind the filters and forms (FR-2.4). Mirrors of ReferenceData.java.

export interface Department {
  id: number;
  code: string;
  name: string;
  parentDepartmentId: number | null;
}

export interface Location {
  id: number;
  countryCode: string;
  countryName: string;
  city: string;
  currencyCode: string;
}

export interface JobRole {
  id: number;
  title: string;
  jobFamily: string;
  jobLevel: string;
}
