import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { EmployeeListComponent } from './employee-list.component';
import { DEPARTMENTS, JOB_ROLES, LOCATIONS, listItem, page } from '../../../../testing/fixtures';
import { expectKeyboardScrollRegion, expectNoHorizontalOverflow, setHostWidth } from '../../../../testing/layout';

// Section 4 (responsive web), FR-2.2: the directory stays a dense table on a desktop and scrolls
// inside its own box on a phone, so the page itself never scrolls sideways.
describe('EmployeeListComponent layout at phone, tablet and desktop widths', () => {
  let fixture: ComponentFixture<EmployeeListComponent>;
  let http: HttpTestingController;

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends HTMLElement>(selector: string): T => el().querySelector(selector) as T;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmployeeListComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmployeeListComponent);
    await fixture.whenStable();
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
    http.expectOne(r => r.url === '/api/v1/employees').flush(page([
      listItem({ id: 1, lastName: 'Byron', email: 'ada.byron.with.a.long.address@example-company.com' }),
      listItem({ id: 2, lastName: 'Hopper' }),
    ], 10000));
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  for (const width of [360, 768]) {
    it(`at ${width}px the page does not scroll sideways: only the table box scrolls`, () => {
      expectNoHorizontalOverflow(setHostWidth(fixture, width), { ignore: '.table-scroll' });
    });
  }

  it('at 360px the table sits in a box that scrolls sideways', () => {
    setHostWidth(fixture, 360);
    const box = q('.table-scroll');

    expect(box.contains(q('table'))).toBeTrue();
    expect(getComputedStyle(box).overflowX).toBe('auto');
    expect(box.scrollWidth).toBeGreaterThan(box.clientWidth);
  });

  it('at 1280px the table fits its box and needs no scrolling', () => {
    setHostWidth(fixture, 1280);
    const box = q('.table-scroll');

    expect(box.scrollWidth).toBeLessThanOrEqual(box.clientWidth + 1);
  });

  it('at 1280px the filter fields still sit on as few rows as they did before the mobile layout', () => {
    setHostWidth(fixture, 1280);
    const tops = new Set(Array.from(el().querySelectorAll('.filters mat-form-field')).map(f => Math.round(f.getBoundingClientRect().top)));

    expect(tops.size).toBeLessThanOrEqual(2);
  });

  it('at 360px every filter field spans the filter bar', () => {
    setHostWidth(fixture, 360);
    const bar = q('.filters');
    const inner = bar.clientWidth - parseFloat(getComputedStyle(bar).paddingLeft) - parseFloat(getComputedStyle(bar).paddingRight);
    const fields = Array.from(el().querySelectorAll('.filters mat-form-field')) as HTMLElement[];

    expect(fields.length).toBe(6);
    for (const field of fields) {
      expect(field.getBoundingClientRect().width).toBeCloseTo(inner, 0);
    }
  });

  it('at 768px the search field keeps its own width instead of stretching', () => {
    setHostWidth(fixture, 768);

    expect(q('.filter-search').getBoundingClientRect().width).toBeLessThanOrEqual(420);
  });

  it('at 360px the New employee button sits under the title and spans the width', () => {
    const host = setHostWidth(fixture, 360);
    const button = q('#new-emp-btn');

    expect(button.getBoundingClientRect().top).toBeGreaterThanOrEqual(q('h1').getBoundingClientRect().bottom);
    expect(button.getBoundingClientRect().width).toBeCloseTo(host.getBoundingClientRect().width, 0);
    expect(button.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
  });

  it('at 1280px the New employee button stays beside the title', () => {
    setHostWidth(fixture, 1280);

    expect(q('#new-emp-btn').getBoundingClientRect().top).toBeLessThan(q('h1').getBoundingClientRect().bottom);
  });

  it('at 360px the first column is the employee code and stays readable without wrapping', () => {
    setHostWidth(fixture, 360);
    const cell = q('td.mat-column-employeeCode');

    expect(getComputedStyle(cell).whiteSpace).toBe('nowrap');
    expect(cell.scrollWidth).toBeLessThanOrEqual(cell.clientWidth + 1);
    expect(parseFloat(getComputedStyle(cell).fontSize)).toBeGreaterThanOrEqual(14);
  });

  it('at 360px the paginator fits the page', () => {
    setHostWidth(fixture, 360);
    const paginator = q('#emp-paginator');

    expect(paginator.scrollWidth).toBeLessThanOrEqual(paginator.clientWidth + 1);
  });

  it('at 360px the directory table box is a focusable, labelled region with a visible focus outline', () => {
    setHostWidth(fixture, 360);

    expectKeyboardScrollRegion(q('.table-scroll'));
    expect(q('.table-scroll').getAttribute('aria-label')).toBe('Employee directory table, scrolls horizontally');
  });

  it('at 360px the page-size picker is still available, visible and inside the page', () => {
    const host = setHostWidth(fixture, 360);
    const picker = q('#emp-paginator .mat-mdc-paginator-page-size-select');
    const rect = picker.getBoundingClientRect();
    expect(getComputedStyle(picker.closest('.mat-mdc-paginator-page-size') as HTMLElement).display).not.toBe('none');

    expect(rect.width).toBeGreaterThan(0);
    expect(rect.height).toBeGreaterThanOrEqual(44);
    expect(rect.right).toBeLessThanOrEqual(host.getBoundingClientRect().right);
    expectNoHorizontalOverflow(host, { ignore: '.table-scroll' });
  });

  it('at 1280px the employee code column is left as it was before the mobile layout: it may wrap', () => {
    setHostWidth(fixture, 1280);

    expect(getComputedStyle(q('td.mat-column-employeeCode')).whiteSpace).toBe('normal');
  });
});
