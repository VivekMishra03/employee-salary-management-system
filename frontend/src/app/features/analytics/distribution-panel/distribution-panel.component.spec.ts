import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { DistributionPanelComponent } from './distribution-panel.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { buckets } from '../../../../testing/fixtures';

describe('DistributionPanelComponent', () => {
  let fixture: ComponentFixture<DistributionPanelComponent>;
  let component: DistributionPanelComponent;
  let http: HttpTestingController;

  const requests = (): TestRequest[] => http.match(r => r.url === '/api/v1/analytics/distribution');
  const oneRequest = (): TestRequest => {
    const found = requests();
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
      imports: [DistributionPanelComponent],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DistributionPanelComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    http.verify();
  });

  it('FR-4.3 / FR-4.7: asks for 12 buckets by default, with the filter', async () => {
    await open({ jobLevel: 'L4' });

    const req = oneRequest();
    expect(req.request.params.get('buckets')).toBe('12');
    expect(req.request.params.get('jobLevel')).toBe('L4');
    expect(el().querySelector('mat-progress-bar')).not.toBeNull();
    req.flush(buckets());
  });

  it('FR-4.3: draws one bar per bucket, labelled with its bounds and counting its employees', async () => {
    await open();
    oneRequest().flush(buckets());
    await fixture.whenStable();

    expect(text(el().querySelectorAll('app-bar-chart .bar-label'))).toEqual([
      '50,000.00 – 75,000.00',
      '75,000.00 – 100,000.00',
      '100,000.00 – 125,000.00',
    ]);
    expect(text(el().querySelectorAll('app-bar-chart .bar-value'))).toEqual(['12', '30', '8']);
  });

  it('FR-4.3: the bucket count is changed after a pause in typing and the histogram is fetched again', async () => {
    await open();
    oneRequest().flush(buckets());
    await fixture.whenStable();
    jasmine.clock().install();

    component.bucketsCtrl.setValue(2);
    component.bucketsCtrl.setValue(20);
    TestBed.tick();
    expect(requests()).toEqual([]);

    jasmine.clock().tick(400);
    TestBed.tick();

    const req = oneRequest();
    expect(req.request.params.get('buckets')).toBe('20');
    req.flush(buckets());
  });

  it('FR-4.3: 2 and 50 are accepted, the two ends of the range the API allows', async () => {
    await open();
    oneRequest().flush(buckets());
    await fixture.whenStable();
    jasmine.clock().install();

    for (const value of [2, 50]) {
      component.bucketsCtrl.setValue(value);
      jasmine.clock().tick(400);
      TestBed.tick();
      const req = oneRequest();
      expect(req.request.params.get('buckets')).toBe(String(value));
      req.flush(buckets());
    }
  });

  it('FR-4.3: a count outside 2 to 50, a fraction or nothing sends no request and says what is allowed', async () => {
    await open();
    oneRequest().flush(buckets());
    await fixture.whenStable();
    jasmine.clock().install();

    for (const bad of [1, 51, 0, -3, 2.5, null]) {
      component.bucketsCtrl.setValue(bad);
      jasmine.clock().tick(400);
      TestBed.tick();
      expect(requests()).withContext(`value ${bad}`).toEqual([]);
    }
    jasmine.clock().uninstall();
    await fixture.whenStable();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('whole number from 2 to 50');
  });

  it('FR-4.3: a valid count after an invalid one clears the message and fetches', async () => {
    await open();
    oneRequest().flush(buckets());
    await fixture.whenStable();
    jasmine.clock().install();

    component.bucketsCtrl.setValue(99);
    component.bucketsCtrl.setValue(8);
    jasmine.clock().tick(400);
    TestBed.tick();
    const req = oneRequest();
    expect(req.request.params.get('buckets')).toBe('8');
    req.flush(buckets());
    jasmine.clock().uninstall();
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')).toBeNull();
  });

  it('FR-4.7: a new filter keeps the bucket count that was chosen', async () => {
    await open();
    oneRequest().flush(buckets());
    await fixture.whenStable();
    jasmine.clock().install();
    component.bucketsCtrl.setValue(30);
    jasmine.clock().tick(400);
    TestBed.tick();
    oneRequest().flush(buckets());
    jasmine.clock().uninstall();

    fixture.componentRef.setInput('filter', { countryCode: 'US' });
    await fixture.whenStable();

    const req = oneRequest();
    expect(req.request.params.get('buckets')).toBe('30');
    expect(req.request.params.get('countryCode')).toBe('US');
    req.flush(buckets());
  });

  it('FR-4.3: an empty slice says nobody matches instead of drawing an empty chart', async () => {
    await open({ q: 'nobody' });
    oneRequest().flush([]);
    await fixture.whenStable();

    expect(el().textContent).toContain('No employees match these filters');
    expect(el().querySelector('app-bar-chart')).toBeNull();
  });

  it('FR-4.7: a failed request shows the server message in this panel only', async () => {
    await open();
    oneRequest().flush({ code: 'INVALID_BUCKETS', detail: 'buckets must be between 2 and 50' },
      { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(el().querySelector('[role="alert"]')?.textContent).toContain('buckets must be between 2 and 50');
    expect(el().querySelector('app-bar-chart')).toBeNull();
  });
});
