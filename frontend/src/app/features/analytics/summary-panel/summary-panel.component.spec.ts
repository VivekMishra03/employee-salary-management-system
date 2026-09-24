import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { SummaryPanelComponent } from './summary-panel.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { EMPTY_SUMMARY, summaryStats } from '../../../../testing/fixtures';
import { asRgb, paletteVar } from '../../../../testing/palette';

describe('SummaryPanelComponent', () => {
  let fixture: ComponentFixture<SummaryPanelComponent>;
  let http: HttpTestingController;

  const summaryRequest = (): TestRequest => http.expectOne(r => r.url === '/api/v1/analytics/summary');
  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const stat = (name: string): string =>
    el().querySelector(`[data-stat="${name}"] .value`)?.textContent?.trim() ?? '(missing)';

  async function open(filter: AnalyticsFilter = {}): Promise<void> {
    fixture.componentRef.setInput('filter', filter);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SummaryPanelComponent],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SummaryPanelComponent);
  });

  afterEach(() => http.verify());

  it('FR-4.1 / FR-4.7: asks the summary endpoint for the current filter, and shows a loading bar until it answers', async () => {
    await open({ departmentId: 2, countryCode: 'DE' });

    const req = summaryRequest();
    expect(req.request.params.get('departmentId')).toBe('2');
    expect(req.request.params.get('countryCode')).toBe('DE');
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    req.flush(summaryStats());
  });

  it('FR-4.1: shows headcount, total payroll and mean, median, p25 and p75, money to two decimals', async () => {
    await open();
    summaryRequest().flush(summaryStats());
    await fixture.whenStable();

    expect(stat('headcount')).toBe('3');
    expect(stat('totalPayroll')).toBe('300,000.50');
    expect(stat('mean')).toBe('100,000.17');
    expect(stat('median')).toBe('99,000.00');
    expect(stat('p25')).toBe('90,000.25');
    expect(stat('p75')).toBe('110,000.00');
    expect(el().querySelector('mat-progress-bar')).toBeNull();
  });

  it('FR-4.1: says the figures are USD, so a reader does not take them for local currency', async () => {
    await open();
    summaryRequest().flush(summaryStats());
    await fixture.whenStable();

    expect(el().textContent).toContain('USD');
  });

  it('FR-4.1: an empty slice shows dashes and says nobody matches, never NaN or a zero pay figure', async () => {
    await open({ q: 'nobody' });
    summaryRequest().flush(EMPTY_SUMMARY);
    await fixture.whenStable();

    for (const name of ['totalPayroll', 'mean', 'median', 'p25', 'p75']) {
      expect(stat(name)).toBe('—');
    }
    expect(stat('headcount')).toBe('0');
    expect(el().textContent).toContain('No employees match these filters');
    expect(el().textContent).not.toContain('NaN');
  });

  it('FR-4.1: a slice with people does not show the "no employees" message', async () => {
    await open();
    summaryRequest().flush(summaryStats());
    await fixture.whenStable();

    expect(el().textContent).not.toContain('No employees match these filters');
  });

  it('FR-4.7: a failed request shows the server message in this panel and no figures', async () => {
    await open();
    summaryRequest().flush({ code: 'INTERNAL_ERROR', detail: 'Analytics are unavailable' },
      { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    const alert = el().querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Analytics are unavailable');
    expect(el().querySelector('[data-stat]')).toBeNull();
  });

  it('requirements assumption 2: the rate-date sentence waits for the answer, so a loading panel claims no date', async () => {
    await open();

    expect(el().textContent).not.toContain('Exchange rate');
    summaryRequest().flush(summaryStats({ ratesAsOf: '2026-09-01' }));
    await fixture.whenStable();

    expect(el().textContent).toContain('Exchange rates as of 1 September 2026.');
  });

  it('requirements assumption 2: a failed summary claims no rate date either way', async () => {
    await open();
    summaryRequest().flush({ code: 'INTERNAL_ERROR', detail: 'Analytics are unavailable' },
      { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(el().textContent).not.toContain('Exchange rate');
  });

  it('FR-4.7: changing the filter fetches again, and an answer to the superseded filter is discarded', async () => {
    await open({ departmentId: 1 });

    fixture.componentRef.setInput('filter', { departmentId: 2 });
    await fixture.whenStable();
    const [older, newer] = http.match(r => r.url === '/api/v1/analytics/summary');
    expect(older.request.params.get('departmentId')).toBe('1');
    expect(newer.request.params.get('departmentId')).toBe('2');
    newer.flush(summaryStats({ headcount: 7 }));
    await fixture.whenStable();

    expect(older.cancelled).toBeTrue();
    expect(stat('headcount')).toBe('7');
  });

  // Look and feel: colour helps a reader scan, but every card keeps its text label and value.
  it('the stat cards are colour-coded: headcount blue, payroll teal, mean and median violet, percentiles slate', async () => {
    await open();
    summaryRequest().flush(summaryStats());
    await fixture.whenStable();

    const tint = (name: string): string =>
      getComputedStyle(el().querySelector(`[data-stat="${name}"]`) as HTMLElement).backgroundColor;
    expect(tint('headcount')).toBe(asRgb(paletteVar('--acme-tint-blue')));
    expect(tint('totalPayroll')).toBe(asRgb(paletteVar('--acme-tint-teal')));
    expect(tint('mean')).toBe(asRgb(paletteVar('--acme-tint-violet')));
    expect(tint('median')).toBe(asRgb(paletteVar('--acme-tint-violet')));
    expect(tint('p25')).toBe(asRgb(paletteVar('--acme-tint-slate')));
    expect(tint('p75')).toBe(asRgb(paletteVar('--acme-tint-slate')));
  });
});
