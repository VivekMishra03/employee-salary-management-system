import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Observable } from 'rxjs';
import { AnalyticsService } from './analytics.service';
import { AnalyticsFilter, AnalyticsSummary, PayBandReport, TrendPoint } from '../models/analytics.model';

describe('AnalyticsService', () => {
  let service: AnalyticsService;
  let http: HttpTestingController;

  const FULL_FILTER: AnalyticsFilter = {
    q: 'ada', departmentId: 4, countryCode: 'DE', status: 'TERMINATED', employmentType: 'PART_TIME', jobLevel: 'L3',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AnalyticsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('FR-4.1 / FR-4.7: summary GETs /analytics/summary with every filter under the same names as /employees', () => {
    let result: AnalyticsSummary | undefined;
    service.summary(FULL_FILTER).subscribe(r => (result = r));

    const req = http.expectOne(r => r.url === '/api/v1/analytics/summary');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('ada');
    expect(req.request.params.get('departmentId')).toBe('4');
    expect(req.request.params.get('countryCode')).toBe('DE');
    expect(req.request.params.get('status')).toBe('TERMINATED');
    expect(req.request.params.get('employmentType')).toBe('PART_TIME');
    expect(req.request.params.get('jobLevel')).toBe('L3');
    req.flush({ headcount: 3, totalPayroll: 300000.5, mean: 100000.17, median: 99000, p25: 90000, p75: 110000 });

    expect(result?.headcount).toBe(3);
    expect(result?.totalPayroll).toBe(300000.5);
  });

  it('FR-4.7: an empty filter sends no query parameters at all', () => {
    service.summary({}).subscribe();

    const req = http.expectOne(r => r.url === '/api/v1/analytics/summary');
    expect(req.request.params.keys()).toEqual([]);
    req.flush({ headcount: 0, totalPayroll: 0, mean: null, median: null, p25: null, p75: null });
  });

  it('FR-4.7: undefined, null and empty-string filter values are omitted rather than sent as empty parameters', () => {
    const sloppy = { q: '', departmentId: undefined, countryCode: '', jobLevel: null } as unknown as AnalyticsFilter;
    service.summary(sloppy).subscribe();

    const req = http.expectOne(r => r.url === '/api/v1/analytics/summary');
    expect(req.request.params.keys()).toEqual([]);
    req.flush({});
  });

  // FR-4.7: one filter drives all six panels, so every method must send every filter, under /employees' names.
  const METHODS: readonly { name: string; path: string; call: (f: AnalyticsFilter) => Observable<unknown> }[] = [
    { name: 'summary', path: 'summary', call: f => service.summary(f) },
    { name: 'byGroup', path: 'by-group', call: f => service.byGroup('DEPARTMENT', f) },
    { name: 'distribution', path: 'distribution', call: f => service.distribution(10, f) },
    { name: 'payBands', path: 'pay-bands', call: f => service.payBands(f, { page: 0, size: 20 }) },
    { name: 'genderGap', path: 'gender-gap', call: f => service.genderGap('JOB_LEVEL', f) },
    { name: 'trend', path: 'trend', call: f => service.trend(f, '2021-09-24', '2026-09-24', 'MONTH') },
  ];
  const FILTER_KEYS = ['q', 'departmentId', 'countryCode', 'status', 'employmentType', 'jobLevel'];

  for (const method of METHODS) {
    it(`FR-4.7: ${method.name} sends all six filters under the names /employees uses`, () => {
      method.call(FULL_FILTER).subscribe();

      const req = http.expectOne(r => r.url === `/api/v1/analytics/${method.path}`);
      expect(FILTER_KEYS.map(key => [key, req.request.params.get(key)])).toEqual([
        ['q', 'ada'], ['departmentId', '4'], ['countryCode', 'DE'],
        ['status', 'TERMINATED'], ['employmentType', 'PART_TIME'], ['jobLevel', 'L3'],
      ]);
      req.flush([]);
    });

    it(`FR-4.7: ${method.name} omits filters that are empty, undefined or null instead of sending them blank`, () => {
      const sloppy = { q: '', departmentId: undefined, countryCode: '', status: null, employmentType: '', jobLevel: null };
      method.call(sloppy as unknown as AnalyticsFilter).subscribe();

      const req = http.expectOne(r => r.url === `/api/v1/analytics/${method.path}`);
      expect(FILTER_KEYS.filter(key => req.request.params.has(key))).toEqual([]);
      req.flush([]);
    });
  }

  it('FR-4.2: byGroup passes groupBy alongside the filter', () => {
    service.byGroup('JOB_LEVEL', { departmentId: 2 }).subscribe();

    const req = http.expectOne(r => r.url === '/api/v1/analytics/by-group');
    expect(req.request.params.get('groupBy')).toBe('JOB_LEVEL');
    expect(req.request.params.get('departmentId')).toBe('2');
    expect(req.request.params.keys().sort()).toEqual(['departmentId', 'groupBy']);
    req.flush([]);
  });

  it('FR-4.3: distribution passes the bucket count as "buckets"', () => {
    service.distribution(12, {}).subscribe();

    const req = http.expectOne(r => r.url === '/api/v1/analytics/distribution');
    expect(req.request.params.get('buckets')).toBe('12');
    req.flush([]);
  });

  it('FR-4.4: payBands asks for one page; adherence is sent only when chosen', () => {
    let report: PayBandReport | undefined;
    service.payBands({ countryCode: 'DE' }, { page: 2, size: 20 }).subscribe(r => (report = r));

    let req = http.expectOne(r => r.url === '/api/v1/analytics/pay-bands');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.get('countryCode')).toBe('DE');
    expect(req.request.params.has('adherence')).toBeFalse();
    req.flush({
      counts: { below: 1, within: 2, above: 3, noBand: 4 },
      employees: { content: [], page: 2, size: 20, totalElements: 10, totalPages: 1 },
    });
    expect(report?.counts.noBand).toBe(4);

    service.payBands({}, { page: 0, size: 20, adherence: 'BELOW' }).subscribe();
    req = http.expectOne(r => r.url === '/api/v1/analytics/pay-bands');
    expect(req.request.params.get('adherence')).toBe('BELOW');
    req.flush({});
  });

  it('FR-4.5: genderGap passes groupBy alongside the filter', () => {
    service.genderGap('DEPARTMENT', { jobLevel: 'L2' }).subscribe();

    const req = http.expectOne(r => r.url === '/api/v1/analytics/gender-gap');
    expect(req.request.params.get('groupBy')).toBe('DEPARTMENT');
    expect(req.request.params.get('jobLevel')).toBe('L2');
    req.flush([]);
  });

  it('FR-4.6: trend passes from, to and interval, with the filter', () => {
    let points: TrendPoint[] | undefined;
    service.trend({ status: 'ACTIVE' }, '2021-09-24', '2026-09-24', 'QUARTER').subscribe(p => (points = p));

    const req = http.expectOne(r => r.url === '/api/v1/analytics/trend');
    expect(req.request.params.get('from')).toBe('2021-09-24');
    expect(req.request.params.get('to')).toBe('2026-09-24');
    expect(req.request.params.get('interval')).toBe('QUARTER');
    expect(req.request.params.get('status')).toBe('ACTIVE');
    req.flush([{ period: '2026-Q3', totalPayrollUsd: 1234.5, avgIncreasePct: null }]);

    expect(points?.[0].avgIncreasePct).toBeNull();
  });
});
