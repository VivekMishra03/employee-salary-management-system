import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { AnalyticsFiltersComponent } from './analytics-filters.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { DEPARTMENTS, JOB_ROLES, LOCATIONS } from '../../../../testing/fixtures';

describe('AnalyticsFiltersComponent', () => {
  let fixture: ComponentFixture<AnalyticsFiltersComponent>;
  let component: AnalyticsFiltersComponent;
  let http: HttpTestingController;
  let emitted: AnalyticsFilter[];

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnalyticsFiltersComponent],
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AnalyticsFiltersComponent);
    component = fixture.componentInstance;
    emitted = [];
    component.filterChange.subscribe(f => emitted.push(f));
    await fixture.whenStable();
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
    await fixture.whenStable();
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    http.verify();
  });

  it('FR-4.7: nothing is emitted until a filter changes: the dashboard starts on the unfiltered default slice', () => {
    expect(emitted).toEqual([]);
  });

  it('FR-4.7: filter options come from the reference lists, with distinct countries and job levels', () => {
    expect(component.departments().map(d => d.name)).toEqual(['Engineering', 'Sales']);
    expect(component.countries().map(c => c.code)).toEqual(['DE', 'US']);
    expect(component.jobLevels()).toEqual(['L3', 'L4']);
  });

  it('FR-4.7: the six filters are search, department, country, status, type and level', () => {
    const labels = Array.from(el().querySelectorAll('mat-label')).map(l => l.textContent?.trim());
    expect(labels).toEqual(['Search', 'Department', 'Country', 'Status', 'Type', 'Level']);
  });

  it('FR-4.7: department, country, type and level apply at once and combine', () => {
    component.departmentCtrl.setValue(2);
    component.countryCtrl.setValue('DE');
    component.typeCtrl.setValue('PART_TIME');
    component.levelCtrl.setValue('L4');

    expect(emitted[emitted.length - 1]).toEqual({
      departmentId: 2, countryCode: 'DE', employmentType: 'PART_TIME', jobLevel: 'L4',
    });
  });

  it('FR-4.7: choosing "All" again drops that constraint', () => {
    component.departmentCtrl.setValue(2);
    component.countryCtrl.setValue('DE');
    component.departmentCtrl.setValue(null);

    expect(emitted[emitted.length - 1]).toEqual({ countryCode: 'DE' });
  });

  it('FR-4.7: the search is debounced, trimmed and sent as q', () => {
    jasmine.clock().install();

    component.searchCtrl.setValue('ad');
    component.searchCtrl.setValue('  ada ');
    expect(emitted).toEqual([]);

    jasmine.clock().tick(400);

    expect(emitted).toEqual([{ q: 'ada' }]);
  });

  it('FR-4.7: clearing the search removes q, and text that differs only by spaces is not a new search', () => {
    jasmine.clock().install();
    component.searchCtrl.setValue('ada');
    jasmine.clock().tick(400);

    component.searchCtrl.setValue('ada ');
    jasmine.clock().tick(400);
    expect(emitted).toEqual([{ q: 'ada' }]);

    component.searchCtrl.setValue('');
    jasmine.clock().tick(400);
    expect(emitted).toEqual([{ q: 'ada' }, {}]);
  });

  it('FR-4.7: a filter that ends up identical to the last one emitted is not emitted twice', () => {
    jasmine.clock().install();
    component.searchCtrl.setValue('ada');
    component.departmentCtrl.setValue(1); // picks up the typed text at once...
    jasmine.clock().tick(400); // ...so the debounced search adds nothing new

    expect(emitted).toEqual([{ q: 'ada', departmentId: 1 }]);
  });

  it('FR-4.7 / ADR-0013: the status defaults to "Active + on leave" and sends no status, so terminated staff are not counted', () => {
    expect(el().querySelector('#filter-status .mat-mdc-select-value-text')?.textContent?.trim())
      .toBe('Active + on leave (default)');
    component.departmentCtrl.setValue(1);

    expect(emitted[emitted.length - 1]).toEqual({ departmentId: 1 });
    expect(Object.keys(emitted[emitted.length - 1])).not.toContain('status');
  });

  it('FR-4.7: the status offers the default, Active, On leave and Terminated, and picking one sends it', async () => {
    (el().querySelector('#filter-status .mat-mdc-select-trigger') as HTMLElement).click();
    await fixture.whenStable();
    const options = Array.from(document.querySelectorAll('mat-option')).map(o => o.textContent?.trim());
    expect(options).toEqual(['Active + on leave (default)', 'Active', 'On leave', 'Terminated']);

    (Array.from(document.querySelectorAll('mat-option'))[3] as HTMLElement).click();
    await fixture.whenStable();

    expect(emitted[emitted.length - 1]).toEqual({ status: 'TERMINATED' });
  });

  it('FR-4.7: going back to the default status drops the status parameter again', () => {
    component.statusCtrl.setValue('ACTIVE');
    component.statusCtrl.setValue('DEFAULT');

    expect(emitted).toEqual([{ status: 'ACTIVE' }, {}]);
  });
});
