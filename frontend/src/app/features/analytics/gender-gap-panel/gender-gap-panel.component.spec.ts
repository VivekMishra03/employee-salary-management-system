import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { GenderGapPanelComponent } from './gender-gap-panel.component';
import { AnalyticsFilter, GenderGapStat } from '../../../core/models/analytics.model';
import { genderGaps } from '../../../../testing/fixtures';
import { asRgb, paletteVar } from '../../../../testing/palette';

describe('GenderGapPanelComponent', () => {
  let fixture: ComponentFixture<GenderGapPanelComponent>;
  let http: HttpTestingController;

  const requests = (): TestRequest[] => http.match(r => r.url === '/api/v1/analytics/gender-gap');
  const oneRequest = (): TestRequest => {
    const found = requests();
    expect(found.length).toBe(1);
    return found[0];
  };
  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (nodes: NodeListOf<Element>): string[] => Array.from(nodes).map(n => n.textContent?.trim() ?? '');
  const rowText = (index: number): string =>
    text(el().querySelectorAll('table.gaps tbody tr')[index].querySelectorAll('th, td')).join(' ');

  async function open(filter: AnalyticsFilter = {}, body: GenderGapStat[] = genderGaps()): Promise<void> {
    fixture.componentRef.setInput('filter', filter);
    await fixture.whenStable();
    oneRequest().flush(body);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GenderGapPanelComponent],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GenderGapPanelComponent);
  });

  afterEach(() => http.verify());

  it('FR-4.5 / FR-4.7: starts grouped by department, with the filter, and shows a loading bar', async () => {
    fixture.componentRef.setInput('filter', { countryCode: 'US' });
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('groupBy')).toBe('DEPARTMENT');
    expect(req.request.params.get('countryCode')).toBe('US');
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    req.flush([]);
  });

  it('FR-4.5: offers department and job level only, the two groupings the API supports', async () => {
    await open();

    expect(text(el().querySelectorAll('mat-button-toggle'))).toEqual(['Department', 'Job level']);
    (el().querySelector('mat-button-toggle[value="JOB_LEVEL"] button') as HTMLButtonElement).click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('groupBy')).toBe('JOB_LEVEL');
    req.flush([]);
  });

  it('FR-4.5: a measurable group shows both headcounts and the mean and median gap as signed percentages', async () => {
    await open();

    const cells = Array.from(el().querySelectorAll('table.gaps tbody tr:first-child th, table.gaps tbody tr:first-child td'));
    expect(cells.map(c => c.querySelector('.gap-value')?.textContent?.trim() ?? c.textContent?.trim())).toEqual([
      'Engineering', '30', '20', '+4.25%', '-3.10%',
    ]);
  });

  it('FR-4.5: the gap is put into words - women paid less for a positive gap, more for a negative one', async () => {
    await open();

    expect(text(el().querySelectorAll('table.gaps tbody tr:first-child .gap-words'))).toEqual([
      'Women paid 4.25% less', 'Women paid 3.10% more',
    ]);
  });

  it('FR-4.5: a group with no defined gap (a zero male average) says so rather than showing zero', async () => {
    await open({}, [{
      key: '3', label: 'Volunteers', maleCount: 6, femaleCount: 7, meanGapPct: null, medianGapPct: null, suppressed: false,
    }]);

    expect(rowText(0)).toContain('Not defined for this group');
    expect(rowText(0)).not.toContain('0.00%');
  });

  it('FR-4.5: a suppressed group shows only its name and that it was suppressed, and no number at all', async () => {
    await open();

    expect(rowText(1)).toBe('Legal Suppressed: group too small');
    expect(rowText(1)).not.toMatch(/\d|%/);
  });

  it('FR-4.5: a suppressed row never shows a count or gap even if the server sent them (defence in depth)', async () => {
    await open({}, [{
      key: '2', label: 'Legal', maleCount: 3, femaleCount: 2, meanGapPct: 41.5, medianGapPct: 12.25, suppressed: true,
    }]);

    expect(rowText(0)).toBe('Legal Suppressed: group too small');
    expect(el().textContent).not.toContain('41.50');
    expect(el().textContent).not.toContain('12.25');
  });

  it('FR-4.5: a note explains that a minimum group size applies, without stating a number', async () => {
    await open();

    const note = el().querySelector('.gap-note')?.textContent ?? '';
    expect(note).toMatch(/minimum group size/i);
    expect(note).not.toMatch(/\d/);
  });

  it('FR-4.5: a note explains what a positive gap means', async () => {
    await open();

    expect(el().querySelector('.gap-note')?.textContent).toMatch(/positive.*women.*less/i);
  });

  it('FR-4.5: no groups says so', async () => {
    await open({ q: 'nobody' }, []);

    expect(el().textContent).toContain('No employees match these filters');
    expect(el().querySelector('table.gaps')).toBeNull();
  });

  it('FR-4.7: a failed request shows the server message in this panel only', async () => {
    fixture.componentRef.setInput('filter', {});
    await fixture.whenStable();
    oneRequest().flush({ code: 'UNSUPPORTED_GROUP_BY', detail: 'groupBy must be DEPARTMENT or JOB_LEVEL' },
      { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('groupBy must be DEPARTMENT or JOB_LEVEL');
    expect(el().querySelector('table.gaps')).toBeNull();
  });

  // Look and feel: a pay gap is not a good or bad number, so its sign carries no colour judgement.
  it('gap figures are the ordinary text colour whether the gap is positive or negative', async () => {
    await open();

    const colours = Array.from(el().querySelectorAll<HTMLElement>('.gap-value')).map(v => getComputedStyle(v).color);
    expect(text(el().querySelectorAll('.gap-value'))).toEqual(['+4.25%', '-3.10%']);
    expect(colours).toEqual([asRgb(paletteVar('--acme-text')), asRgb(paletteVar('--acme-text'))]);
  });
});
