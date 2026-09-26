import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { EmployeeDetailComponent } from './employee-detail.component';
import { RecordSalaryDialogComponent } from '../record-salary-dialog/record-salary-dialog.component';
import { EmployeeFormDialogComponent } from '../employee-form-dialog/employee-form-dialog.component';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { detail, salary } from '../../../../testing/fixtures';

describe('EmployeeDetailComponent', () => {
  let fixture: ComponentFixture<EmployeeDetailComponent>;
  let component: EmployeeDetailComponent;
  let http: HttpTestingController;
  let dialog: MatDialog;

  const text = (): string => fixture.nativeElement.textContent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmployeeDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '42' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    dialog = TestBed.inject(MatDialog);
    fixture = TestBed.createComponent(EmployeeDetailComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('FR-2.5: shows profile, current compensation, manager and direct reports for the routed id', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();

    expect(text()).toContain('Ada Byron');
    expect(text()).toContain('E00042');
    expect(text()).toContain('Engineering');
    expect(text()).toContain('Senior Software Engineer');
    expect(text()).toContain('95,000.00 EUR');       // base, local currency (FR-3.4)
    expect(text()).toContain('103,550.50');          // annualised in base currency (FR-3.4)
    expect(text()).toContain('Grace Hopper');
    expect(text()).toContain('Linus Benedict');
    expect(text()).toContain('Margaret Hamilton');
  });

  it('FR-3.1 / ADR-0011: the full history is listed and a superseded zero-length record is marked, not hidden', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    (fixture.nativeElement.querySelectorAll('.mat-mdc-tab')[1] as HTMLElement).click();
    await fixture.whenStable();

    const rows = Array.from(fixture.nativeElement.querySelectorAll('table.salary-history tr.mat-mdc-row')) as HTMLElement[];
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Current');
    expect(rows[1].classList).toContain('superseded');
    expect(rows[1].textContent).toContain('Superseded');
    expect(rows[2].textContent).toContain('NEW_HIRE');
    expect(rows[2].classList).not.toContain('superseded');
  });

  it('FR-2.5: an employee with no salary record says so, and the history tab shows the empty row', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail({ currentSalary: null, salaryHistory: [] }));
    await fixture.whenStable();

    expect(text()).toContain('No salary recorded');

    (fixture.nativeElement.querySelectorAll('.mat-mdc-tab')[1] as HTMLElement).click();
    await fixture.whenStable();

    // The only body row is the "no data" placeholder; a real salary row would carry a currency code.
    const rows = Array.from(fixture.nativeElement.querySelectorAll('table.salary-history tr.mat-mdc-row')) as HTMLElement[];
    expect(rows.length).toBe(1);
    expect(rows[0].querySelector('td.no-data')?.textContent).toContain('No salary history.');
    expect(rows[0].textContent).not.toContain('EUR');
  });

  it('FR-2.5: an unknown id shows "not found" rather than an empty profile', async () => {
    http.expectOne('/api/v1/employees/42').flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(text()).toContain('Employee not found');
    expect(fixture.nativeElement.querySelector('#add-salary-btn')).toBeNull();
  });

  it('FR-3.2 (UI): "Record salary change" opens the salary dialog for this employee; after a save the history shows the new record', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    const open = spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(true) } as never);

    (fixture.nativeElement.querySelector('#add-salary-btn') as HTMLButtonElement).click();

    expect(open).toHaveBeenCalledWith(RecordSalaryDialogComponent,
      jasmine.objectContaining({ data: { employeeId: 42, employeeName: 'Ada Byron' } }));

    const promoted = salary({
      id: 10, effectiveFrom: '2026-10-01', effectiveTo: null, baseAmount: 120000, changeReason: 'PROMOTION',
    });
    const base = detail();
    const closedPrevious = { ...base.salaryHistory[0], effectiveTo: '2026-10-01' };
    http.expectOne('/api/v1/employees/42').flush(detail({
      currentSalary: promoted,
      salaryHistory: [promoted, closedPrevious, ...base.salaryHistory.slice(1)],
    }));
    await fixture.whenStable();
    (fixture.nativeElement.querySelectorAll('.mat-mdc-tab')[1] as HTMLElement).click();
    await fixture.whenStable();

    const rows = Array.from(fixture.nativeElement.querySelectorAll('table.salary-history tr.mat-mdc-row')) as HTMLElement[];
    expect(rows.length).toBe(4);
    expect(rows[0].textContent).toContain('PROMOTION');
    expect(rows[0].textContent).toContain('120,000.00');
    expect(rows[0].textContent).toContain('Current');
    expect(rows[1].textContent).not.toContain('Current');
  });

  it('FR-2.1: "Edit" opens the employee form with the loaded record and reloads after a save', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    const open = spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(true) } as never);

    (fixture.nativeElement.querySelector('#edit-emp-btn') as HTMLButtonElement).click();

    expect(open).toHaveBeenCalledWith(EmployeeFormDialogComponent,
      jasmine.objectContaining({ data: { employee: jasmine.objectContaining({ id: 42, version: 3 }) } }));
    http.expectOne('/api/v1/employees/42').flush(detail());
  });

  // FR-2.6: the form reports a 409 by closing with 'conflict'. The page must reload, otherwise the
  // next Edit would open with the same stale version and fail with 409 again.
  it('FR-2.6: an edit dialog that closes with "conflict" reloads the employee and the next Edit carries the new version', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    const open = spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of('conflict') } as never);

    (fixture.nativeElement.querySelector('#edit-emp-btn') as HTMLButtonElement).click();

    http.expectOne('/api/v1/employees/42').flush(detail({ version: 4, lastName: 'Lovelace' }));
    await fixture.whenStable();
    expect(text()).toContain('Ada Lovelace');

    (fixture.nativeElement.querySelector('#edit-emp-btn') as HTMLButtonElement).click();

    expect(open.calls.count()).toBe(2);
    const secondCall = open.calls.mostRecent().args[1] as { data: { employee: { version: number; lastName: string } } };
    expect(secondCall.data.employee.version).toBe(4);
    expect(secondCall.data.employee.lastName).toBe('Lovelace');
    http.expectOne('/api/v1/employees/42').flush(detail({ version: 4, lastName: 'Lovelace' }));
  });

  it('FR-2.6: an edit dialog dismissed without a result (backdrop or Escape) still reloads, since a conflict may have gone unreported', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(undefined) } as never);

    (fixture.nativeElement.querySelector('#edit-emp-btn') as HTMLButtonElement).click();

    http.expectOne('/api/v1/employees/42').flush(detail({ version: 4 }));
    await fixture.whenStable();
    expect(component.employee()?.version).toBe(4);
  });

  it('FR-2.6: an edit dialog cancelled with no conflict does not reload', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(false) } as never);

    (fixture.nativeElement.querySelector('#edit-emp-btn') as HTMLButtonElement).click();

    http.expectNone('/api/v1/employees/42');
    expect(component.employee()?.version).toBe(3);
  });

  it('FR-2.1: "Deactivate" asks for confirmation, then soft-deletes and reloads', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(true) } as never);

    (fixture.nativeElement.querySelector('#deactivate-emp-btn') as HTMLButtonElement).click();

    expect(dialog.open).toHaveBeenCalledWith(ConfirmDialogComponent, jasmine.anything());
    const del = http.expectOne('/api/v1/employees/42');
    expect(del.request.method).toBe('DELETE');
    del.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/employees/42').flush(detail({ employmentStatus: 'TERMINATED', terminationDate: '2026-09-23' }));
    await fixture.whenStable();

    expect(text()).toContain('TERMINATED');
  });

  it('FR-2.1: a declined confirmation changes nothing', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail());
    await fixture.whenStable();
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(false) } as never);

    (fixture.nativeElement.querySelector('#deactivate-emp-btn') as HTMLButtonElement).click();

    http.expectNone('/api/v1/employees/42');
    expect(component.employee()?.employmentStatus).toBe('ACTIVE');
  });

  it('ADR-0010: a terminated employee cannot be edited or deactivated again, but salary history stays recordable', async () => {
    http.expectOne('/api/v1/employees/42').flush(detail({ employmentStatus: 'TERMINATED', terminationDate: '2026-01-31' }));
    await fixture.whenStable();

    expect((fixture.nativeElement.querySelector('#edit-emp-btn') as HTMLButtonElement).disabled).toBeTrue();
    expect((fixture.nativeElement.querySelector('#deactivate-emp-btn') as HTMLButtonElement).disabled).toBeTrue();
    expect((fixture.nativeElement.querySelector('#add-salary-btn') as HTMLButtonElement).disabled).toBeFalse();
    expect(component.employee()?.terminationDate).toBe('2026-01-31');
  });
});
