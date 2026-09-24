import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { AnalyticsFiltersComponent } from './analytics-filters.component';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { DEPARTMENTS, JOB_ROLES, LOCATIONS } from '../../../../testing/fixtures';
import { setHostWidth, textRightEdge } from '../../../../testing/layout';

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

  it('FR-4.7: the search field is labelled "Search", shows the fields it covers as a placeholder, and names them for assistive technology', () => {
    const input = el().querySelector('#analytics-search') as HTMLInputElement;

    expect(input.placeholder).toBe('Name, code or email');
    expect(input.getAttribute('aria-label')).toBe('Search by name, employee code or email');
  });

  describe('layout in a real browser', () => {
    const searchField = (): HTMLElement => el().querySelector('.filter-search') as HTMLElement;
    const icon = (): HTMLElement => searchField().querySelector('.mat-mdc-form-field-icon-suffix mat-icon') as HTMLElement;

    for (const width of [1280, 360]) {
      it(`FR-4.7: at ${width}px the search label ends before the search icon instead of running under it`, () => {
        setHostWidth(fixture, width);
        const label = searchField().querySelector('label.mdc-floating-label') as HTMLElement;

        expect(textRightEdge(label)).toBeLessThanOrEqual(icon().getBoundingClientRect().left);
      });

      // The value text is an inline element, so scrollWidth/clientWidth are always 0 for it; the text's
      // own painted extent against the dropdown arrow is what shows whether the words are cut off.
      it(`FR-4.7: at ${width}px the Status select shows its whole default text, ending before the dropdown arrow`, () => {
        setHostWidth(fixture, width);
        const text = el().querySelector('#filter-status .mat-mdc-select-value-text') as HTMLElement;
        const arrow = el().querySelector('#filter-status .mat-mdc-select-arrow-wrapper') as HTMLElement;

        expect(text.textContent?.trim()).toBe('Active + on leave (default)');
        expect(textRightEdge(text)).toBeLessThanOrEqual(arrow.getBoundingClientRect().left);
      });
    }

    it('FR-4.7: the search input padding and box sizing do not push its box under the search icon', () => {
      setHostWidth(fixture, 360);
      const input = el().querySelector('#analytics-search') as HTMLInputElement;

      expect(input.getBoundingClientRect().right).toBeLessThanOrEqual(icon().getBoundingClientRect().left);
    });

    it('FR-4.7: a 60-character search overflows the input, is clipped with an ellipsis, and the clip edge stays clear of the search icon', () => {
      setHostWidth(fixture, 360);
      const input = el().querySelector('#analytics-search') as HTMLInputElement;
      input.value = 'x'.repeat(60);
      const style = getComputedStyle(input);
      const clipEdge = input.getBoundingClientRect().right - parseFloat(style.paddingRight) - parseFloat(style.borderRightWidth);

      expect(input.scrollWidth).toBeGreaterThan(input.clientWidth);
      expect(style.textOverflow).toBe('ellipsis');
      expect(icon().getBoundingClientRect().left - clipEdge).toBeGreaterThanOrEqual(8);
    });
  });
});
