import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { AnalyticsComponent } from './analytics.component';
import { TODAY } from '../../core/util/today';
import {
  DEPARTMENTS, JOB_ROLES, LOCATIONS, buckets, genderGaps, groupStats, payBandReport, summaryStats, trendPoints,
} from '../../../testing/fixtures';
import { expectKeyboardScrollRegion, expectNoHorizontalOverflow, setHostWidth } from '../../../testing/layout';

// Section 4 (responsive web), FR-4: every dashboard panel on a phone, a tablet and a desktop.
describe('AnalyticsComponent layout at phone, tablet and desktop widths', () => {
  let fixture: ComponentFixture<AnalyticsComponent>;

  const el = (): HTMLElement => fixture.nativeElement;
  const all = (selector: string): HTMLElement[] => Array.from(el().querySelectorAll(selector)) as HTMLElement[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnalyticsComponent],
      providers: [
        provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: TODAY, useValue: () => new Date(2026, 8, 24) },
      ],
    }).compileComponents();
    const http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AnalyticsComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
    const bodies: Record<string, object> = {
      summary: summaryStats(), 'by-group': groupStats(), distribution: buckets(),
      'pay-bands': payBandReport(), 'gender-gap': genderGaps(), trend: trendPoints(),
    };
    for (const req of http.match(r => r.url.startsWith('/api/v1/analytics/')) as TestRequest[]) {
      req.flush(bodies[req.request.url.replace('/api/v1/analytics/', '')]);
    }
    await fixture.whenStable();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  for (const width of [360, 768]) {
    it(`at ${width}px no panel scrolls the page sideways: only the table boxes scroll`, () => {
      expectNoHorizontalOverflow(setHostWidth(fixture, width), { ignore: '.table-scroll' });
    });
  }

  it('at 1280px the page still fits', () => {
    expectNoHorizontalOverflow(setHostWidth(fixture, 1280), { ignore: '.table-scroll' });
  });

  it('every table is inside a box that scrolls sideways', () => {
    setHostWidth(fixture, 360);
    const tables = all('table').filter(t => !t.classList.contains('visually-hidden'));

    expect(tables.length).toBe(4);
    for (const table of tables) {
      const box = table.closest('.table-scroll') as HTMLElement;
      expect(box).withContext(table.className).not.toBeNull();
      expect(getComputedStyle(box).overflowX).toBe('auto');
    }
  });

  it('at 360px the panels form a single column', () => {
    setHostWidth(fixture, 360);
    const panels = all('.dashboard > *');

    expect(panels.length).toBe(6);
    expect(new Set(panels.map(p => Math.round(p.getBoundingClientRect().left))).size).toBe(1);
  });

  it('at 360px every filter field spans the filter bar', () => {
    setHostWidth(fixture, 360);
    const bar = el().querySelector('.filters') as HTMLElement;
    const inner = bar.clientWidth - parseFloat(getComputedStyle(bar).paddingLeft) - parseFloat(getComputedStyle(bar).paddingRight);
    const fields = all('.filters mat-form-field');

    expect(fields.length).toBe(6);
    for (const field of fields) {
      expect(field.getBoundingClientRect().width).toBeCloseTo(inner, 0);
    }
  });

  it('at 360px the summary figures are two to a row, and at 300px one to a row', () => {
    setHostWidth(fixture, 360);
    const lefts = (): number => new Set(all('.stat').map(s => Math.round(s.getBoundingClientRect().left))).size;
    expect(lefts()).toBe(2);

    setHostWidth(fixture, 300);
    expect(lefts()).toBe(1);
  });

  it('at 360px the group toggles fill the panel and stay inside it', () => {
    setHostWidth(fixture, 360);
    const groups = all('mat-button-toggle-group');

    expect(groups.length).toBe(3);
    for (const group of groups) {
      const panel = group.closest('mat-card') as HTMLElement;
      expect(group.getBoundingClientRect().right).toBeLessThanOrEqual(panel.getBoundingClientRect().right);
    }
    for (const toggle of all('mat-button-toggle button')) {
      expect(toggle.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
    }
  });

  it('at 360px the trend date pickers and the interval toggle are stacked, one to a row', () => {
    setHostWidth(fixture, 360);
    const controls = el().querySelectorAll('app-trend-panel .panel-controls > *');
    const tops = Array.from(controls).map(c => Math.round(c.getBoundingClientRect().top));

    expect(tops.length).toBe(3);
    expect(new Set(tops).size).toBe(3);
  });

  it('at 1280px the trend date pickers keep their 170px width', () => {
    setHostWidth(fixture, 1280);

    expect((el().querySelector('app-trend-panel .date') as HTMLElement).getBoundingClientRect().width).toBeCloseTo(170, 0);
  });

  it('at 360px the pay-band tiles are at least 44px tall and stay inside the panel', () => {
    setHostWidth(fixture, 360);
    const panel = el().querySelector('app-pay-bands-panel mat-card') as HTMLElement;

    expect(all('.count').length).toBe(4);
    for (const tile of all('.count')) {
      expect(tile.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
      expect(tile.getBoundingClientRect().right).toBeLessThanOrEqual(panel.getBoundingClientRect().right);
    }
  });

  it('at 360px the pay-band paginator fits its panel', () => {
    setHostWidth(fixture, 360);
    const paginator = el().querySelector('app-pay-bands-panel mat-paginator') as HTMLElement;

    expect(paginator.scrollWidth).toBeLessThanOrEqual(paginator.clientWidth + 1);
  });

  it('charts are never wider than the page, at any width', () => {
    for (const width of [300, 360, 768, 1280]) {
      const host = setHostWidth(fixture, width);
      const charts = [...all('app-bar-chart'), ...all('app-line-chart svg')];

      expect(charts.length).toBe(4);
      for (const chart of charts) {
        expect(chart.getBoundingClientRect().width).withContext(`${width}px`).toBeLessThanOrEqual(host.getBoundingClientRect().width);
      }
    }
  });

  it('at 360px the line chart axis labels are drawn at least 10px tall on screen', () => {
    setHostWidth(fixture, 360);
    const svg = el().querySelector('app-line-chart svg') as SVGSVGElement;
    const scale = svg.getBoundingClientRect().width / svg.viewBox.baseVal.width;
    const label = svg.querySelector('.y-label') as SVGTextElement;

    expect(parseFloat(getComputedStyle(label).fontSize) * scale).toBeGreaterThanOrEqual(10);
  });

  it('at 360px running text and table text are at least 14px', () => {
    setHostWidth(fixture, 360);
    const selectors = [
      '.panel-note', '.groups td', '.groups th', '.gaps td', '.gap-words', '.employees td', '.trend td',
      '.stat dt', '.count .label', '.bar-row',
    ];

    for (const selector of selectors) {
      const found = all(selector);
      expect(found.length).withContext(selector).toBeGreaterThan(0);
      for (const node of found) {
        expect(parseFloat(getComputedStyle(node).fontSize)).withContext(selector).toBeGreaterThanOrEqual(14);
      }
    }
  });

  it('at 1280px the dense 13px table text of the desktop layout is unchanged, even in a half-width panel', () => {
    setHostWidth(fixture, 1280);

    expect(getComputedStyle(el().querySelector('.groups td') as HTMLElement).fontSize).toBe('13px');
    expect(getComputedStyle(el().querySelector('.employees td') as HTMLElement).fontSize).toBe('13px');
  });

  it('every scrolling table box is a focusable, labelled region with a visible focus outline', () => {
    setHostWidth(fixture, 360);
    const boxes = all('.table-scroll');

    expect(boxes.length).toBe(4);
    for (const box of boxes) {
      expectKeyboardScrollRegion(box);
    }
    expect(boxes.map(b => b.getAttribute('aria-label'))).toEqual([
      'Pay comparison table, scrolls horizontally',
      'Employees by pay band table, scrolls horizontally',
      'Gender pay gap table, scrolls horizontally',
      'Pay trend table, scrolls horizontally',
    ]);
  });

  it('at 360px the pay-band paginator styling really applies: centred, wrapping, tight margins, 12px page-size label', () => {
    setHostWidth(fixture, 360);
    const q = (sel: string): HTMLElement => el().querySelector(`app-pay-bands-panel ${sel}`) as HTMLElement;

    expect(getComputedStyle(q('.mat-mdc-paginator-container')).justifyContent).toBe('center');
    expect(getComputedStyle(q('.mat-mdc-paginator-container')).flexWrap).toBe('wrap');
    expect(getComputedStyle(q('.mat-mdc-paginator-range-label')).marginLeft).toBe('8px');
    expect(getComputedStyle(q('.mat-mdc-paginator-range-label')).marginRight).toBe('8px');
    expect(getComputedStyle(q('.mat-mdc-paginator-page-size')).marginRight).toBe('0px');
    expect(getComputedStyle(q('.mat-mdc-paginator-page-size-label')).fontSize).toBe('12px');
  });

  it('at 360px the pay-band page-size picker is still available, visible and inside the panel', () => {
    setHostWidth(fixture, 360);
    const picker = el().querySelector('app-pay-bands-panel .mat-mdc-paginator-page-size-select') as HTMLElement;
    const rect = picker.getBoundingClientRect();
    expect(getComputedStyle(picker.closest('.mat-mdc-paginator-page-size') as HTMLElement).display).not.toBe('none');
    const panel = (el().querySelector('app-pay-bands-panel mat-card') as HTMLElement).getBoundingClientRect();

    expect(rect.width).toBeGreaterThan(0);
    expect(rect.height).toBeGreaterThanOrEqual(44);
    expect(rect.right).toBeLessThanOrEqual(panel.right);
    expectNoHorizontalOverflow(el(), { ignore: '.table-scroll' });
  });

  // FR-4.5: a suppressed group discloses nothing at any width. Written after the layout work; it passed on first run.
  it('at 360px a suppressed group shows only its label and the suppression notice, and no gender text leaks elsewhere', () => {
    setHostWidth(fixture, 360);
    const rows = all('app-gender-gap-panel tbody tr');
    const suppressed = rows.find(r => r.textContent?.includes('Legal')) as HTMLElement;

    expect(suppressed.querySelector('th')?.textContent?.trim()).toBe('Legal');
    expect(suppressed.querySelector('td')?.textContent?.trim()).toBe('Suppressed: group too small');
    expect(suppressed.querySelectorAll('td').length).toBe(1);
    expect(suppressed.querySelector('.num, .gap-value, .gap-words')).toBeNull();
    const others = all('.dashboard > *, .filters').filter(n => n.tagName !== 'APP-GENDER-GAP-PANEL');
    for (const node of others) {
      expect(node.textContent).withContext(node.tagName).not.toMatch(/\b(gender|male|female|men|women|suppressed)\b/i);
    }
  });
});
