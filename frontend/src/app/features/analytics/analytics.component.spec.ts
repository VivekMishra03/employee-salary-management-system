import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { AnalyticsComponent } from './analytics.component';
import { AnalyticsFiltersComponent } from './analytics-filters/analytics-filters.component';
import { TODAY } from '../../core/util/today';
import {
  DEPARTMENTS, EMPTY_SUMMARY, JOB_ROLES, LOCATIONS, buckets, genderGaps, groupStats, payBandReport, summaryStats,
  trendPoints,
} from '../../../testing/fixtures';

const ENDPOINTS = ['summary', 'by-group', 'distribution', 'pay-bands', 'gender-gap', 'trend'];

describe('AnalyticsComponent', () => {
  let fixture: ComponentFixture<AnalyticsComponent>;
  let http: HttpTestingController;

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const analyticsRequests = (): TestRequest[] => http.match(r => r.url.startsWith('/api/v1/analytics/'));
  const endpoint = (req: TestRequest): string => req.request.url.replace('/api/v1/analytics/', '');
  const filters = (): AnalyticsFiltersComponent =>
    fixture.debugElement.query(By.directive(AnalyticsFiltersComponent)).componentInstance;

  /** Answers each analytics request with a fixture of its own shape. */
  function answer(requests: TestRequest[], overrides: Record<string, (req: TestRequest) => void> = {}): void {
    const bodies: Record<string, object> = {
      summary: summaryStats(),
      'by-group': groupStats(),
      distribution: buckets(),
      'pay-bands': payBandReport(),
      'gender-gap': genderGaps(),
      trend: trendPoints(),
    };
    for (const req of requests) {
      if (req.cancelled) {
        continue;
      }
      const name = endpoint(req);
      if (overrides[name]) {
        overrides[name](req);
      } else {
        req.flush(bodies[name]);
      }
    }
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnalyticsComponent],
      providers: [
        provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: TODAY, useValue: () => new Date(2026, 8, 24) },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AnalyticsComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    http.verify();
  });

  it('FR-4.1 - FR-4.6: opening the page asks each of the six analytics endpoints once, with no filter', async () => {
    const requests = analyticsRequests();

    expect(requests.map(endpoint).sort()).toEqual([...ENDPOINTS].sort());
    for (const req of requests) {
      for (const key of ['q', 'departmentId', 'countryCode', 'status', 'employmentType', 'jobLevel']) {
        expect(req.request.params.has(key)).withContext(`${endpoint(req)} ${key}`).toBeFalse();
      }
    }
    answer(requests);
  });

  it('FR-4.7: the default filter sends no status parameter to any endpoint', () => {
    const requests = analyticsRequests();

    expect(requests.every(r => !r.request.params.has('status'))).toBeTrue();
    answer(requests);
  });

  it('FR-4.7: changing a filter reloads all six panels with it', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    filters().departmentCtrl.setValue(2);
    await fixture.whenStable();

    const requests = analyticsRequests();
    expect(requests.map(endpoint).sort()).toEqual([...ENDPOINTS].sort());
    for (const req of requests) {
      expect(req.request.params.get('departmentId')).withContext(endpoint(req)).toBe('2');
    }
    answer(requests);
  });

  it('FR-4.7: choosing Terminated is sent to every endpoint as the status', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    filters().statusCtrl.setValue('TERMINATED');
    await fixture.whenStable();

    const requests = analyticsRequests();
    expect(requests.length).toBe(6);
    expect(requests.every(r => r.request.params.get('status') === 'TERMINATED')).toBeTrue();
    answer(requests);
  });

  it('FR-4.7: the search reloads the panels once, after a pause in typing', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();
    jasmine.clock().install();

    filters().searchCtrl.setValue('ad');
    filters().searchCtrl.setValue('ada');
    TestBed.tick();
    expect(analyticsRequests()).toEqual([]);

    jasmine.clock().tick(400);
    TestBed.tick();

    const requests = analyticsRequests();
    expect(requests.length).toBe(6);
    expect(requests.every(r => r.request.params.get('q') === 'ada')).toBeTrue();
    answer(requests);
  });

  it('FR-4.7: a newer filter abandons the requests of the older one, so a slow answer cannot overwrite it', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    filters().departmentCtrl.setValue(1);
    await fixture.whenStable();
    const older = analyticsRequests();
    filters().departmentCtrl.setValue(2);
    await fixture.whenStable();
    const newer = analyticsRequests();

    expect(older.length).toBe(6);
    expect(older.every(r => r.cancelled)).toBeTrue();
    expect(newer.every(r => r.request.params.get('departmentId') === '2')).toBeTrue();
    answer(newer);
  });

  it('FR-4.7: one endpoint failing shows its message in its own panel and leaves the others intact', async () => {
    answer(analyticsRequests(), {
      summary: req => req.flush(
        { code: 'INTERNAL_ERROR', detail: 'Summary is down' }, { status: 500, statusText: 'Server Error' }),
    });
    await fixture.whenStable();

    const alerts = Array.from(el().querySelectorAll('[role="alert"]')).map(a => a.textContent?.trim());
    expect(alerts).toEqual(['Summary is down']);
    expect(el().querySelector('app-summary-panel [role="alert"]')).not.toBeNull();
    expect(el().querySelector('app-comparison-panel table.groups')).not.toBeNull();
    expect(el().querySelector('app-pay-bands-panel table.employees')).not.toBeNull();
    expect(el().querySelector('app-gender-gap-panel table.gaps')).not.toBeNull();
    expect(el().querySelector('app-trend-panel table.trend')).not.toBeNull();
  });

  it('FR-4.1: an empty slice shows the empty message and no NaN anywhere', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    filters().departmentCtrl.setValue(1);
    await fixture.whenStable();
    answer(analyticsRequests(), {
      summary: req => req.flush(EMPTY_SUMMARY),
      'by-group': req => req.flush([]),
      distribution: req => req.flush([]),
      'pay-bands': req => req.flush({ ...payBandReport([], 0), counts: { below: 0, within: 0, above: 0, noBand: 0 } }),
      'gender-gap': req => req.flush([]),
      trend: req => req.flush([]),
    });
    await fixture.whenStable();

    expect(el().textContent).toContain('No employees match these filters');
    expect(el().textContent).not.toContain('NaN');
  });

  it('FR-4.5 / requirements assumption 3: gender appears in the gender pay gap panel and nowhere else on the page', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    const clone = el().cloneNode(true) as HTMLElement;
    clone.querySelector('app-gender-gap-panel')?.remove();

    expect(el().querySelector('app-gender-gap-panel')?.textContent).toMatch(/\bwomen\b/i);
    expect(clone.textContent).not.toMatch(/\b(gender|women|woman|men|man|male|female)\b/i);
  });

  const fxNote = (): string => el().querySelector('.fx-note')?.textContent?.replace(/\s+/g, ' ').trim() ?? '(missing)';

  it('FR-3.5 / requirements assumption 2: says that amounts are converted at the rate effective when each salary was recorded', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    expect(fxNote()).toContain('Amounts are converted to USD at the rate effective when each salary was recorded');
  });

  it('requirements assumption 2: shows the date the exchange rates are as of, as the calendar day the server reported', async () => {
    answer(analyticsRequests(), { summary: req => req.flush(summaryStats({ ratesAsOf: '2026-03-05' })) });
    await fixture.whenStable();

    expect(fxNote()).toContain('Exchange rates as of 5 March 2026.');
    expect(fxNote()).not.toContain('unavailable');
  });

  it('requirements assumption 2: with no rates at all it says the date is unavailable instead of inventing one', async () => {
    answer(analyticsRequests(), { summary: req => req.flush(summaryStats({ ratesAsOf: null })) });
    await fixture.whenStable();

    expect(fxNote()).toContain('Exchange-rate date unavailable.');
    expect(fxNote()).not.toContain('as of');
  });

  it('requirements assumption 2: the rate date is still shown when the filters match nobody, because it describes the rates and not the slice', async () => {
    answer(analyticsRequests(), { summary: req => req.flush({ ...EMPTY_SUMMARY, ratesAsOf: '2026-09-01' }) });
    await fixture.whenStable();

    expect(el().querySelector('app-summary-panel')?.textContent).toContain('No employees match these filters');
    expect(fxNote()).toContain('Exchange rates as of 1 September 2026.');
  });

  it('FR-4: shows the title and every panel', async () => {
    answer(analyticsRequests());
    await fixture.whenStable();

    expect(el().querySelector('h1')?.textContent).toContain('Pay analytics');
    for (const tag of ['app-analytics-filters', 'app-summary-panel', 'app-comparison-panel', 'app-distribution-panel',
      'app-pay-bands-panel', 'app-gender-gap-panel', 'app-trend-panel']) {
      expect(el().querySelector(tag)).withContext(tag).not.toBeNull();
    }
  });
});
