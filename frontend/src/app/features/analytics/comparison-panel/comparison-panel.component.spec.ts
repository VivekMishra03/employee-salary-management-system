import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComparisonPanelComponent } from './comparison-panel.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { groupStats } from '../../../../testing/fixtures';

describe('ComparisonPanelComponent', () => {
  let fixture: ComponentFixture<ComparisonPanelComponent>;
  let http: HttpTestingController;

  const byGroupRequests = (): TestRequest[] => http.match(r => r.url === '/api/v1/analytics/by-group');
  const oneRequest = (): TestRequest => {
    const found = byGroupRequests();
    expect(found.length).toBe(1);
    return found[0];
  };
  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (nodes: NodeListOf<Element>): string[] => Array.from(nodes).map(n => n.textContent?.trim() ?? '');

  async function open(filter: AnalyticsFilter = {}): Promise<void> {
    fixture.componentRef.setInput('filter', filter);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ComparisonPanelComponent],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ComparisonPanelComponent);
  });

  afterEach(() => http.verify());

  it('FR-4.2 / FR-4.7: starts grouped by department and sends the filter with it', async () => {
    await open({ countryCode: 'DE' });

    const req = oneRequest();
    expect(req.request.params.get('groupBy')).toBe('DEPARTMENT');
    expect(req.request.params.get('countryCode')).toBe('DE');
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    req.flush(groupStats());
  });

  it('FR-4.2: a table lists every group with its headcount and its median pay', async () => {
    await open();
    oneRequest().flush(groupStats());
    await fixture.whenStable();

    const rows = Array.from(el().querySelectorAll('table.groups tbody tr'));
    expect(rows.map(r => text(r.querySelectorAll('th, td')))).toEqual([
      ['Engineering', '40', '120,000.00', '125,000.50'],
      ['Sales', '25', '80,000.00', '82,000.00'],
    ]);
  });

  it('FR-4.2: a bar chart draws the median of each group', async () => {
    await open();
    oneRequest().flush(groupStats());
    await fixture.whenStable();

    expect(text(el().querySelectorAll('app-bar-chart .bar-label'))).toEqual(['Engineering', 'Sales']);
    expect(text(el().querySelectorAll('app-bar-chart .bar-value'))).toEqual(['120,000.00', '80,000.00']);
  });

  it('FR-4.2: the grouping toggle offers department, country and job level, and picking one asks the API for it', async () => {
    await open();
    oneRequest().flush(groupStats());
    await fixture.whenStable();

    const options = text(el().querySelectorAll('mat-button-toggle'));
    expect(options).toEqual(['Department', 'Country', 'Job level']);

    (el().querySelector('mat-button-toggle[value="COUNTRY"] button') as HTMLButtonElement).click();
    await fixture.whenStable();
    let req = oneRequest();
    expect(req.request.params.get('groupBy')).toBe('COUNTRY');
    req.flush([]);
    await fixture.whenStable();

    (el().querySelector('mat-button-toggle[value="JOB_LEVEL"] button') as HTMLButtonElement).click();
    await fixture.whenStable();
    req = oneRequest();
    expect(req.request.params.get('groupBy')).toBe('JOB_LEVEL');
    req.flush([]);
  });

  it('FR-4.2: a group with no median is a dash, not a zero-height bar labelled 0', async () => {
    await open();
    oneRequest().flush([{ key: '9', label: 'Ghost', headcount: 0, median: null, mean: null }]);
    await fixture.whenStable();

    expect(text(el().querySelectorAll('table.groups tbody td'))).toEqual(['0', '—', '—']);
    expect(text(el().querySelectorAll('app-bar-chart .bar-value'))).toEqual(['—']);
  });

  it('FR-4.2: no groups says so instead of showing an empty table', async () => {
    await open({ q: 'nobody' });
    oneRequest().flush([]);
    await fixture.whenStable();

    expect(el().textContent).toContain('No employees match these filters');
    expect(el().querySelector('table.groups')).toBeNull();
  });

  it('FR-4.7: a failed request shows the server message in this panel only', async () => {
    await open();
    oneRequest().flush({ code: 'UNSUPPORTED_GROUP_BY', detail: 'groupBy must be one of ...' },
      { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('groupBy must be one of');
    expect(el().querySelector('table.groups')).toBeNull();
  });

  it('FR-4.7: a new filter re-fetches with the grouping that was chosen', async () => {
    await open();
    oneRequest().flush(groupStats());
    await fixture.whenStable();
    (el().querySelector('mat-button-toggle[value="JOB_LEVEL"] button') as HTMLButtonElement).click();
    await fixture.whenStable();
    oneRequest().flush([]);
    await fixture.whenStable();

    fixture.componentRef.setInput('filter', { jobLevel: 'L4' });
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('groupBy')).toBe('JOB_LEVEL');
    expect(req.request.params.get('jobLevel')).toBe('L4');
    req.flush([]);
  });
});
