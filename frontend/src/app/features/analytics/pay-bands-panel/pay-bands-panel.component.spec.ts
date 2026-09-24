import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { PayBandsPanelComponent } from './pay-bands-panel.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { payBandEmployee, payBandReport } from '../../../../testing/fixtures';
import { asRgb, paletteVar } from '../../../../testing/palette';

describe('PayBandsPanelComponent', () => {
  let fixture: ComponentFixture<PayBandsPanelComponent>;
  let component: PayBandsPanelComponent;
  let http: HttpTestingController;

  const requests = (): TestRequest[] => http.match(r => r.url === '/api/v1/analytics/pay-bands');
  const oneRequest = (): TestRequest => {
    const found = requests();
    expect(found.length).toBe(1);
    return found[0];
  };
  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (nodes: NodeListOf<Element>): string[] => Array.from(nodes).map(n => n.textContent?.trim() ?? '');
  const countButton = (adherence: string): HTMLButtonElement =>
    el().querySelector(`button.count[data-adherence="${adherence}"]`) as HTMLButtonElement;
  const rows = (): string[][] =>
    Array.from(el().querySelectorAll('table.employees tbody tr')).map(r => text(r.querySelectorAll('th, td')));

  async function open(filter: AnalyticsFilter = {}): Promise<void> {
    fixture.componentRef.setInput('filter', filter);
    await fixture.whenStable();
  }

  async function flush(body = payBandReport()): Promise<void> {
    oneRequest().flush(body);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PayBandsPanelComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PayBandsPanelComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => http.verify());

  it('FR-4.4 / FR-4.7: asks for the first page of 20 with the filter, and no adherence', async () => {
    await open({ departmentId: 2 });

    const req = oneRequest();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.get('departmentId')).toBe('2');
    expect(req.request.params.has('adherence')).toBeFalse();
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    req.flush(payBandReport());
  });

  it('FR-4.4: shows the count below, within, above and with no band for the whole slice', async () => {
    await open();
    await flush();

    expect(text(el().querySelectorAll('button.count'))).toEqual([
      'Below band 4', 'Within band 30', 'Above band 2', 'No band 1',
    ]);
  });

  it('FR-4.4: names each employee with a link to their page, role, level, country, pay, band and compa-ratio', async () => {
    await open();
    await flush();

    expect(rows()).toEqual([[
      'Ada Byron', 'E00042', 'Senior Software Engineer', 'L4', 'DE',
      '95,000.00 EUR', '80,000.00', '100,000.00', '120,000.00', '0.95', 'Within band',
    ]]);
    const link = el().querySelector('table.employees tbody a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/employees/42');
    expect(link.textContent?.trim()).toBe('Ada Byron');
  });

  it('FR-4.4: the compa-ratio is shown to two decimals', async () => {
    await open();
    await flush(payBandReport([payBandEmployee({ compaRatio: 1.2 }), payBandEmployee({ employeeId: 43, compaRatio: 0.9166 })]));

    expect(rows().map(r => r[9])).toEqual(['1.20', '0.92']);
  });

  it('FR-4.4: an employee with no band has dashes for the band and the compa-ratio, never a guessed figure', async () => {
    await open();
    await flush(payBandReport([payBandEmployee({
      bandMin: null, bandMid: null, bandMax: null, compaRatio: null, adherence: 'NO_BAND',
    })]));

    const [row] = rows();
    expect(row.slice(6, 10)).toEqual(['—', '—', '—', '—']);
    expect(row[10]).toBe('No band');
    expect(row.join(' ')).not.toContain('NaN');
  });

  it('FR-4.4: the adherence chip carries the class the stylesheet colours by', async () => {
    await open();
    await flush(payBandReport([payBandEmployee({ adherence: 'BELOW', compaRatio: 0.7 })]));

    expect(el().querySelector('table.employees mat-chip')?.getAttribute('data-adherence')).toBe('BELOW');
  });

  it('FR-2.2 / FR-4.4: only the page the server sent is rendered, and the paginator is told the real total', async () => {
    await open();
    await flush(payBandReport([payBandEmployee()], 10000));

    expect(rows().length).toBe(1);
    expect(el().querySelector('.mat-mdc-paginator-range-label')?.textContent).toContain('10000');
  });

  it('FR-4.4: the paginator drives the page parameter', async () => {
    await open();
    await flush(payBandReport([payBandEmployee()], 100));

    (el().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(payBandReport([payBandEmployee()], 100));
  });

  it('FR-4.4: a new page size is sent, from the first page, and never exceeds the 100 the API allows', async () => {
    await open();
    await flush(payBandReport([payBandEmployee()], 500));

    component.onPage({ pageIndex: 0, pageSize: 100, length: 500 });
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('size')).toBe('100');
    expect(component.pageSizeOptions.every(size => size <= 100)).toBeTrue();
    req.flush(payBandReport([payBandEmployee()], 500));
  });

  it('FR-4.4: choosing a count filters the table to that adherence and starts from the first page', async () => {
    await open();
    await flush(payBandReport([payBandEmployee()], 100));
    (el().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
    await fixture.whenStable();
    await flush(payBandReport([payBandEmployee()], 100));

    countButton('BELOW').click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('adherence')).toBe('BELOW');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(payBandReport([payBandEmployee({ adherence: 'BELOW' })]));
    await fixture.whenStable();
    expect(countButton('BELOW').getAttribute('aria-pressed')).toBe('true');
    expect(countButton('WITHIN').getAttribute('aria-pressed')).toBe('false');
  });

  it('FR-4.4: the counts still describe the whole slice while the table is narrowed', async () => {
    await open();
    await flush();
    countButton('ABOVE').click();
    await fixture.whenStable();
    await flush();

    expect(text(el().querySelectorAll('button.count'))).toEqual([
      'Below band 4', 'Within band 30', 'Above band 2', 'No band 1',
    ]);
  });

  it('FR-4.4: choosing the same count again removes the adherence filter', async () => {
    await open();
    await flush();
    countButton('NO_BAND').click();
    await fixture.whenStable();
    const narrowed = oneRequest();
    expect(narrowed.request.params.get('adherence')).toBe('NO_BAND');
    narrowed.flush(payBandReport());
    await fixture.whenStable();

    countButton('NO_BAND').click();
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.has('adherence')).toBeFalse();
    req.flush(payBandReport());
  });

  it('FR-4.7: a new filter goes back to the first page but keeps the chosen adherence', async () => {
    await open();
    await flush(payBandReport([payBandEmployee()], 100));
    countButton('WITHIN').click();
    await fixture.whenStable();
    await flush(payBandReport([payBandEmployee()], 100));
    (el().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
    await fixture.whenStable();
    await flush(payBandReport([payBandEmployee()], 100));

    fixture.componentRef.setInput('filter', { countryCode: 'DE' });
    await fixture.whenStable();

    const found = requests();
    expect(found.length).toBe(1);
    expect(found[0].request.params.get('page')).toBe('0');
    expect(found[0].request.params.get('adherence')).toBe('WITHIN');
    expect(found[0].request.params.get('countryCode')).toBe('DE');
    found[0].flush(payBandReport());
  });

  it('FR-4.4: while another page loads the rows in hand stay visible under a loading bar', async () => {
    await open();
    await flush(payBandReport([payBandEmployee()], 100));

    (el().querySelector('.mat-mdc-paginator-navigation-next') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    expect(rows().length).toBe(1);
    oneRequest().flush(payBandReport([payBandEmployee(), payBandEmployee({ employeeId: 44 })], 100));
  });

  it('FR-4.4: an empty view says so', async () => {
    await open({ q: 'nobody' });
    await flush({ ...payBandReport([], 0), counts: { below: 0, within: 0, above: 0, noBand: 0 } });

    expect(el().textContent).toContain('No employees in this view');
    expect(el().querySelector('table.employees tbody tr')).toBeNull();
  });

  it('FR-4.7: a failed request shows the server message and no stale table or counts', async () => {
    await open();
    await flush();
    fixture.componentRef.setInput('filter', { countryCode: 'DE' });
    await fixture.whenStable();
    oneRequest().flush({ code: 'INTERNAL_ERROR', detail: 'Pay-band service failed' },
      { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Pay-band service failed');
    expect(el().querySelector('table.employees')).toBeNull();
    expect(el().querySelector('button.count')).toBeNull();
  });

  it('NFR-2: the pay is shown in the employee\'s own currency, as sent, with no conversion', async () => {
    await open();
    await flush(payBandReport([payBandEmployee({ annualisedAmount: 60000, currencyCode: 'GBP' })]));

    expect(rows()[0][5]).toBe('60,000.00 GBP');
  });

  // Look and feel: colour only backs up the text label on each chip.
  it('adherence chips use the semantic colours: below amber, within green, above blue, no band grey', async () => {
    await open();
    await flush(payBandReport(['BELOW', 'WITHIN', 'ABOVE', 'NO_BAND'].map((adherence, i) =>
      payBandEmployee({ employeeId: i + 1, adherence: adherence as 'BELOW' })),
    ));

    const chips = Array.from(el().querySelectorAll<HTMLElement>('mat-chip.adherence-chip'));
    const painted = chips.map(chip => [
      chip.textContent?.trim(),
      getComputedStyle(chip).backgroundColor,
      getComputedStyle(chip.querySelector('.mdc-evolution-chip__text-label') as HTMLElement).color,
    ]);
    const pair = (name: string): string[] => [asRgb(paletteVar(`--acme-${name}-bg`)), asRgb(paletteVar(`--acme-${name}`))];
    expect(painted).toEqual([
      ['Below band', ...pair('below')],
      ['Within band', ...pair('within')],
      ['Above band', ...pair('above')],
      ['No band', ...pair('no-band')],
    ]);
  });
});
