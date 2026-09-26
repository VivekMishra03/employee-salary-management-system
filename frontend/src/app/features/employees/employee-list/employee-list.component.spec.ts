import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { EmployeeListComponent } from './employee-list.component';
import { EmployeeFormDialogComponent } from '../employee-form-dialog/employee-form-dialog.component';
import { DEPARTMENTS, JOB_ROLES, LOCATIONS, listItem, page } from '../../../../testing/fixtures';

describe('EmployeeListComponent', () => {
  let fixture: ComponentFixture<EmployeeListComponent>;
  let component: EmployeeListComponent;
  let http: HttpTestingController;
  let router: Router;

  const employeesRequest = (): TestRequest => http.expectOne(r => r.url === '/api/v1/employees');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmployeeListComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(EmployeeListComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();

    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    http.verify();
  });

  it('FR-2.2: asks the server for the first page only and renders exactly that page', async () => {
    const req = employeesRequest();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('sort')).toBe('lastName,asc');

    req.flush(page([listItem({ id: 1, lastName: 'Byron' }), listItem({ id: 2, lastName: 'Hopper' })], 10000));
    await fixture.whenStable();

    const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Byron');
    expect(component.totalElements()).toBe(10000);
    expect(fixture.nativeElement.querySelector('.mat-mdc-paginator-range-label').textContent).toContain('10000');
  });

  it('FR-2.2: the paginator drives the page and size parameters', () => {
    employeesRequest().flush(page([], 10000));

    component.onPage({ pageIndex: 3, pageSize: 50, length: 10000 });

    const req = employeesRequest();
    expect(req.request.params.get('page')).toBe('3');
    expect(req.request.params.get('size')).toBe('50');
    req.flush(page([], 10000, 3, 50));
  });

  it('FR-2.2: sorting is sent to the server and resets to the first page', () => {
    employeesRequest().flush(page([], 10000));
    component.onPage({ pageIndex: 4, pageSize: 25, length: 10000 });
    employeesRequest().flush(page([], 10000, 4));

    component.onSort({ active: 'employeeCode', direction: 'desc' });

    const req = employeesRequest();
    expect(req.request.params.get('sort')).toBe('employeeCode,desc');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([], 10000));
  });

  it('FR-2.2 / ADR-0010: only columns the API can sort by offer a sort header', async () => {
    employeesRequest().flush(page([listItem()]));
    await fixture.whenStable();

    const headers = Array.from(fixture.nativeElement.querySelectorAll('th')) as HTMLElement[];
    const sortable = headers.filter(h => h.classList.contains('mat-sort-header')).map(h => h.textContent?.trim());
    const fixed = headers.filter(h => !h.classList.contains('mat-sort-header')).map(h => h.textContent?.trim());

    expect(sortable).toEqual(['Code', 'Name', 'Email', 'Hired', 'Status', 'Type']);
    expect(fixed).toContain('Department');
    expect(fixed).toContain('Role');
    expect(fixed).toContain('Level');
  });

  it('FR-2.3: free-text search is debounced, sent as q, and restarts from the first page', () => {
    jasmine.clock().install();
    employeesRequest().flush(page([], 10000));
    component.onPage({ pageIndex: 2, pageSize: 25, length: 10000 });
    employeesRequest().flush(page([], 10000, 2));

    component.searchCtrl.setValue('ad');
    component.searchCtrl.setValue('ada');
    http.expectNone(r => r.url === '/api/v1/employees');

    jasmine.clock().tick(400);

    const req = employeesRequest();
    expect(req.request.params.get('q')).toBe('ada');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([]));
  });

  it('FR-2.4: filters combine, apply immediately, and an "All" choice drops the parameter', () => {
    employeesRequest().flush(page([], 10000));

    component.departmentCtrl.setValue(2);
    let req = employeesRequest();
    expect(req.request.params.get('departmentId')).toBe('2');
    req.flush(page([]));

    component.countryCtrl.setValue('DE');
    req = employeesRequest();
    expect(req.request.params.get('departmentId')).toBe('2');
    expect(req.request.params.get('countryCode')).toBe('DE');
    req.flush(page([]));

    component.statusCtrl.setValue('ON_LEAVE');
    component.typeCtrl.setValue('PART_TIME');
    component.levelCtrl.setValue('L4');
    // Three quick changes issue three requests; only the newest may be answered, older ones are cancelled.
    const pending = http.match(r => r.url === '/api/v1/employees');
    expect(pending.length).toBe(3);
    expect(pending[0].cancelled).toBeTrue();
    expect(pending[1].cancelled).toBeTrue();
    expect(pending[2].cancelled).toBeFalse();
    req = pending[pending.length - 1];
    expect(req.request.params.get('status')).toBe('ON_LEAVE');
    expect(req.request.params.get('employmentType')).toBe('PART_TIME');
    expect(req.request.params.get('jobLevel')).toBe('L4');
    req.flush(page([]));

    component.departmentCtrl.setValue(null);
    req = employeesRequest();
    expect(req.request.params.has('departmentId')).toBeFalse();
    expect(req.request.params.get('countryCode')).toBe('DE');
    req.flush(page([]));
  });

  it('FR-2.2: an older response arriving after a newer one is ignored', async () => {
    employeesRequest().flush(page([], 10000));

    component.departmentCtrl.setValue(1);
    component.countryCtrl.setValue('DE');
    const [older, newer] = http.match(r => r.url === '/api/v1/employees');
    expect(older.request.params.get('countryCode')).toBeNull();
    expect(newer.request.params.get('countryCode')).toBe('DE');

    newer.flush(page([listItem({ id: 1, lastName: 'Newest' })], 1));
    await fixture.whenStable();

    // The older query must have been abandoned; were it still live, answering it would replace the rows.
    expect(older.cancelled).toBeTrue();
    if (!older.cancelled) {
      older.flush(page([listItem({ id: 2, lastName: 'Stale' })], 1));
      await fixture.whenStable();
    }
    const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('Newest');
    expect(fixture.nativeElement.textContent).not.toContain('Stale');
  });

  // A failure under new filters must not leave the previous filters' rows on screen: they would be
  // read as the answer to the query that just failed.
  it('FR-2.4: a failed fetch clears the rows and total rather than leaving the results of the previous filters', async () => {
    const snack = spyOn(TestBed.inject(MatSnackBar), 'open').and.stub();
    employeesRequest().flush(page([listItem({ id: 1, lastName: 'Byron' })], 10000));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('tr.mat-mdc-row').length).toBe(1);

    component.departmentCtrl.setValue(2);
    employeesRequest().flush({ code: 'INTERNAL_ERROR', detail: 'Something went wrong' },
      { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(component.totalElements()).toBe(0);
    expect(fixture.nativeElement.textContent).not.toContain('Byron');
    expect(fixture.nativeElement.textContent).toContain('No employees found.');
    expect(component.loading()).toBeFalse();
    expect(snack).toHaveBeenCalledWith('Something went wrong', 'Close', jasmine.anything());
  });

  it('FR-2.3: text that differs only by surrounding spaces is the same search and sends no second request', () => {
    jasmine.clock().install();
    employeesRequest().flush(page([]));

    component.searchCtrl.setValue('ada');
    jasmine.clock().tick(400);
    employeesRequest().flush(page([]));

    component.searchCtrl.setValue('ada ');
    jasmine.clock().tick(400);
    expect(http.match(r => r.url === '/api/v1/employees')).toEqual([]);
  });

  it('FR-2.3: clearing the search box sends one request with no q parameter, from the first page', () => {
    jasmine.clock().install();
    employeesRequest().flush(page([]));

    component.searchCtrl.setValue('ada');
    jasmine.clock().tick(400);
    employeesRequest().flush(page([]));

    component.searchCtrl.setValue('');
    jasmine.clock().tick(400);

    const requests = http.match(r => r.url === '/api/v1/employees');
    expect(requests.length).toBe(1);
    expect(requests[0].request.params.has('q')).toBeFalse();
    expect(requests[0].request.params.get('page')).toBe('0');
    requests[0].flush(page([]));
  });

  it('FR-2.3: typing only a space is the empty search again and sends no request', () => {
    jasmine.clock().install();
    employeesRequest().flush(page([]));

    component.searchCtrl.setValue(' ');
    jasmine.clock().tick(400);
    expect(http.match(r => r.url === '/api/v1/employees')).toEqual([]);
  });

  it('FR-2.4: filter options come from the reference lists — distinct countries and job levels', async () => {
    employeesRequest().flush(page([]));
    await fixture.whenStable();

    expect(component.countries().map(c => c.code)).toEqual(['DE', 'US']);
    expect(component.jobLevels()).toEqual(['L3', 'L4']);
    expect(component.departments().map(d => d.name)).toEqual(['Engineering', 'Sales']);
  });

  it('FR-2.5: clicking a row opens that employee', async () => {
    employeesRequest().flush(page([listItem({ id: 77 })]));
    await fixture.whenStable();

    (fixture.nativeElement.querySelector('tr.mat-mdc-row') as HTMLElement).click();

    expect(router.navigate).toHaveBeenCalledWith(['/employees', 77]);
  });

  it('FR-2.1: "New employee" opens the form dialog and reloads the page when one was created', () => {
    employeesRequest().flush(page([]));
    const dialog = TestBed.inject(MatDialog);
    const open = spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(true) } as never);

    (fixture.nativeElement.querySelector('#new-emp-btn') as HTMLButtonElement).click();

    expect(open).toHaveBeenCalledWith(EmployeeFormDialogComponent, jasmine.objectContaining({ data: { employee: null } }));
    employeesRequest().flush(page([]));
  });
});
