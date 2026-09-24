import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { DateAdapter } from '@angular/material/core';
import { DATE_DEBOUNCE_MS, TrendPanelComponent } from './trend-panel.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { IsoDateAdapter } from '../../../core/util/iso-date-adapter';
import { TODAY } from '../../../core/util/today';
import { trendPoints } from '../../../../testing/fixtures';
import { asRgb, paletteVar } from '../../../../testing/palette';

const day = (y: number, m: number, d: number): Date => new Date(y, m - 1, d);

describe('TrendPanelComponent', () => {
  let fixture: ComponentFixture<TrendPanelComponent>;
  let component: TrendPanelComponent;
  let http: HttpTestingController;

  const requests = (): TestRequest[] => http.match(r => r.url === '/api/v1/analytics/trend');
  const oneRequest = (): TestRequest => {
    const found = requests();
    expect(found.length).toBe(1);
    return found[0];
  };
  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (nodes: NodeListOf<Element>): string[] => Array.from(nodes).map(n => n.textContent?.trim() ?? '');
  const tableRows = (): string[][] =>
    Array.from(el().querySelectorAll('table.trend tbody tr')).map(r => text(r.querySelectorAll('th, td')));
  const charts = (): HTMLElement[] => Array.from(el().querySelectorAll<HTMLElement>('app-line-chart'));
  const dotCount = (chart: HTMLElement): number => chart.querySelectorAll('circle.dot').length;

  async function open(filter: AnalyticsFilter = {}): Promise<void> {
    fixture.componentRef.setInput('filter', filter);
    await fixture.whenStable();
  }

  async function settle(body = trendPoints()): Promise<void> {
    oneRequest().flush(body);
    await fixture.whenStable();
  }

  /** Sets both dates and lets the date debounce elapse, so the resulting request (if any) is open. */
  async function setRange(from: Date | null, to: Date | null): Promise<void> {
    jasmine.clock().install();
    component.form.patchValue({ from, to });
    jasmine.clock().tick(DATE_DEBOUNCE_MS);
    jasmine.clock().uninstall();
    await fixture.whenStable();
  }

  /** Types into a date input the way a person does: text in the field, then the input event. */
  function typeDate(inputId: string, text: string): void {
    const input = el().querySelector(inputId) as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TrendPanelComponent],
      providers: [
        provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting(),
        // NFR-3: the panel reads the date from TODAY, never from the clock.
        { provide: TODAY, useValue: () => day(2026, 9, 24) },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TrendPanelComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    http.verify();
  });

  it('FR-4.6 / NFR-3: by default asks for the five years ending today, monthly, with the filter', async () => {
    await open({ departmentId: 3 });

    const req = oneRequest();
    expect(req.request.params.get('from')).toBe('2021-09-24');
    expect(req.request.params.get('to')).toBe('2026-09-24');
    expect(req.request.params.get('interval')).toBe('MONTH');
    expect(req.request.params.get('departmentId')).toBe('3');
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    req.flush([]);
  });

  it('FR-4.6: a table lists each period with its total payroll and average increase, and a dash where there was no increase', async () => {
    await open();
    await settle();

    expect(tableRows()).toEqual([
      ['2026-01', '1,000,000.00', '—'],
      ['2026-02', '1,050,000.50', '3.50%'],
      ['2026-03', '1,100,000.00', '2.25%'],
    ]);
  });

  it('FR-4.6: one line chart plots payroll (a dot per period) and a second the average increase, with a gap for a null', async () => {
    await open();
    await settle();

    const [payroll, increase] = charts();
    expect(charts().length).toBe(2);
    expect(dotCount(payroll)).toBe(3);
    expect(dotCount(increase)).toBe(2);
    expect(increase.querySelectorAll('path.series').length).toBe(1);
  });

  it('FR-4.6: the interval toggle offers month, quarter and year and re-fetches with the one chosen', async () => {
    await open();
    await settle();
    expect(text(el().querySelectorAll('mat-button-toggle'))).toEqual(['Month', 'Quarter', 'Year']);

    (el().querySelector('mat-button-toggle[value="QUARTER"] button') as HTMLButtonElement).click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('interval')).toBe('QUARTER');
    expect(req.request.params.get('from')).toBe('2021-09-24');
    req.flush([]);
  });

  it('FR-4.6: a new start or end date is sent as the calendar day picked, in ISO form', async () => {
    await open();
    await settle();

    await setRange(day(2024, 1, 1), day(2025, 12, 31));

    const req = oneRequest();
    expect(req.request.params.get('from')).toBe('2024-01-01');
    expect(req.request.params.get('to')).toBe('2025-12-31');
    req.flush([]);
  });

  // What was PARSED is asserted, not only what was sent: the request text is built from local getters, so
  // it reads 2024-01-15 even for a UTC-parsed date in this machine's zone (UTC+5:30). CI runs the suite in
  // Pacific/Auckland and America/Los_Angeles (ci-frontend.yml), one zone either side of UTC. A UTC-parsed date
  // is 13:00 local in Auckland on the 15th at UTC+13 and the 14th in Los Angeles; only local midnight is
  // right in all.
  it('FR-4.6: a date typed as 2024-01-15 is parsed as local midnight on the 15th, in any time zone', async () => {
    await open();
    await settle();

    typeDate('#trend-from', '2024-01-15');

    const parsed = component.form.controls.from.value as Date;
    expect(parsed).toBeInstanceOf(Date);
    expect(parsed.getFullYear()).toBe(2024);
    expect(parsed.getMonth()).toBe(0);
    expect(parsed.getDate()).toBe(15);
    expect(parsed.getHours()).withContext('a UTC-parsed date is not local midnight east of UTC').toBe(0);
    expect(parsed.getMinutes()).withContext('a UTC-parsed date is not local midnight in a half-hour zone').toBe(0);
  });

  it('FR-4.6: the trend date fields read typed text through IsoDateAdapter, not the native adapter', () => {
    expect(fixture.debugElement.injector.get(DateAdapter)).toBeInstanceOf(IsoDateAdapter);
  });

  it('FR-4.6: a date typed as 2024-01-15 is requested as 2024-01-15', async () => {
    await open();
    await settle();

    jasmine.clock().install();
    typeDate('#trend-from', '2024-01-15');
    jasmine.clock().tick(DATE_DEBOUNCE_MS);
    TestBed.tick();

    const req = oneRequest();
    expect(req.request.params.get('from')).toBe('2024-01-15');
    req.flush([]);
  });

  it('FR-4.6: typing a date asks once after a pause, for the finished date, not once per keystroke', async () => {
    await open();
    await settle();

    jasmine.clock().install();
    typeDate('#trend-from', '2024');
    TestBed.tick();
    typeDate('#trend-from', '2024-01-15');
    TestBed.tick();
    expect(requests()).withContext('nothing is asked while the date is still being typed').toEqual([]);

    jasmine.clock().tick(DATE_DEBOUNCE_MS - 1);
    TestBed.tick();
    expect(requests()).withContext('nothing is asked one millisecond before the pause is over').toEqual([]);

    jasmine.clock().tick(1);
    TestBed.tick();

    const req = oneRequest();
    expect(req.request.params.get('from')).toBe('2024-01-15');
    expect(req.request.params.get('to')).toBe('2026-09-24');
    req.flush([]);
  });

  it('FR-4.6: while a typed date is still settling the old answer is not shown as if it were for the new range', async () => {
    await open();
    await settle();

    jasmine.clock().install();
    typeDate('#trend-from', '2024-01-15');
    TestBed.tick();
    fixture.detectChanges();

    expect(el().querySelector('table.trend')).toBeNull();
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    jasmine.clock().tick(DATE_DEBOUNCE_MS);
    TestBed.tick();
    oneRequest().flush([]);
  });

  it('FR-4.6: an invalid range typed in is explained at once and abandons the request in flight, and none follows the pause', async () => {
    await open();
    const [inFlight] = requests();

    jasmine.clock().install();
    typeDate('#trend-to', '2020-01-01');
    TestBed.tick();
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('end date cannot be before the start date');
    expect(inFlight.cancelled).toBeTrue();
    jasmine.clock().tick(DATE_DEBOUNCE_MS);
    TestBed.tick();
    expect(requests()).toEqual([]);
  });

  it('FR-4.6: the interval toggle is not debounced', async () => {
    await open();
    await settle();

    (el().querySelector('mat-button-toggle[value="YEAR"] button') as HTMLButtonElement).click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('interval')).toBe('YEAR');
    req.flush([]);
  });

  it('FR-4.6: an end before the start is explained and nothing is requested', async () => {
    await open();
    await settle();

    await setRange(day(2026, 1, 1), day(2025, 1, 1));

    expect(requests()).toEqual([]);
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('end date cannot be before the start date');
    expect(el().querySelector('table.trend')).toBeNull();
  });

  it('FR-4.6: more than 120 periods is explained and nothing is requested; a coarser interval then makes it valid', async () => {
    await open();
    await settle();

    await setRange(day(2010, 1, 1), day(2026, 9, 24));
    expect(requests()).toEqual([]);
    expect(el().querySelector('[role="alert"]')?.textContent).toMatch(/more than 120 periods/);

    (el().querySelector('mat-button-toggle[value="YEAR"] button') as HTMLButtonElement).click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('interval')).toBe('YEAR');
    expect(req.request.params.get('from')).toBe('2010-01-01');
    expect(el().querySelector('[role="alert"]')).toBeNull();
    req.flush([]);
  });

  it('FR-4.6: exactly 120 months is sent', async () => {
    await open();
    await settle();

    await setRange(day(2016, 1, 1), day(2025, 12, 31));

    expect(oneRequest().request.params.get('to')).toBe('2025-12-31');
  });

  it('FR-4.6: years outside 1900 to 2200 are explained and nothing is requested', async () => {
    await open();
    await settle();

    await setRange(day(1850, 1, 1), day(1860, 1, 1));

    expect(requests()).toEqual([]);
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('1900');
  });

  it('FR-4.6: a missing date (cleared or not a date) asks for both and requests nothing', async () => {
    await open();
    await settle();

    await setRange(null, day(2026, 1, 1));

    expect(requests()).toEqual([]);
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Choose both');
  });

  it('FR-4.6: going invalid abandons a request still in flight, so its answer can never be shown for the wrong range', async () => {
    await open();
    const [inFlight] = requests();

    await setRange(day(2026, 1, 1), day(2025, 1, 1));

    expect(inFlight.cancelled).toBeTrue();
  });

  it('FR-4.6: the server refusing the range is shown with its own message, and no stale table', async () => {
    await open();
    await settle();
    await setRange(day(2024, 1, 1), day(2025, 12, 31));
    oneRequest().flush({ code: 'TREND_SPAN_TOO_LARGE', detail: 'The span covers too many periods' },
      { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('The span covers too many periods');
    expect(el().querySelector('table.trend')).toBeNull();
  });

  it('FR-4.7: a new filter re-fetches for the same range and interval', async () => {
    await open();
    await settle();
    await setRange(day(2024, 1, 1), day(2025, 12, 31));
    oneRequest().flush([]);
    await fixture.whenStable();

    fixture.componentRef.setInput('filter', { jobLevel: 'L5' });
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('jobLevel')).toBe('L5');
    expect(req.request.params.get('from')).toBe('2024-01-01');
    req.flush([]);
  });

  it('FR-4.6: no periods says so instead of drawing empty charts', async () => {
    await open();
    await settle([]);

    expect(el().textContent).toContain('No payroll data for this range');
    expect(charts()).toEqual([]);
  });

  // ADR-0013: the trend counts everyone employed on each period's last day, so the status filter cannot
  // mean "employed now" here; the panel has to say what the figures cover.
  it('FR-4.7 / ADR-0013: says the trend counts everyone employed in each period, and that a status filter applies to current status', async () => {
    await open();
    await settle();

    expect(el().querySelector('.trend-scope')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Counts everyone employed in each period, including people who have since left. '
      + 'A status filter applies to their current status.');
  });

  it('FR-4.7 / ADR-0013: the scope note is there while loading and when the server refuses the range, not only with data', async () => {
    await open();
    expect(el().querySelector('.trend-scope')).not.toBeNull();

    oneRequest().flush({ code: 'TREND_SPAN_TOO_LARGE', detail: 'Too many periods' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')).not.toBeNull();
    expect(el().querySelector('.trend-scope')).not.toBeNull();
  });

  it('FR-4.6: a payroll figure is shown in USD', async () => {
    await open();
    await settle();

    expect(el().querySelector('table.trend thead')?.textContent).toContain('USD');
  });

  // Look and feel: the two series must be told apart by colour as well as by their headings and axes.
  it('the payroll line and the increase line are drawn in two different palette colours', async () => {
    await open();
    await settle();

    const stroke = (chart: HTMLElement): string =>
      getComputedStyle(chart.querySelector('path.series') as SVGPathElement).stroke;
    expect(charts().map(stroke)).toEqual([
      asRgb(paletteVar('--acme-series-payroll')), asRgb(paletteVar('--acme-series-increase')),
    ]);
  });
});
