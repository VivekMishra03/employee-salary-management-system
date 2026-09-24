import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, provideZonelessChangeDetection } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef, MatDialogState } from '@angular/material/dialog';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { EmployeeFormDialogComponent, EmployeeFormDialogData } from './employee-form-dialog.component';
import { DEPARTMENTS, JOB_ROLES, LOCATIONS, detail, listItem, page } from '../../../../testing/fixtures';

describe('EmployeeFormDialogComponent', () => {
  let fixture: ComponentFixture<EmployeeFormDialogComponent>;
  let component: EmployeeFormDialogComponent;
  let http: HttpTestingController;
  let dialogRef: jasmine.SpyObj<MatDialogRef<EmployeeFormDialogComponent>>;
  let snack: jasmine.Spy;
  // The two ways a user can dismiss a dialog without pressing one of its buttons.
  let backdropClicks: Subject<MouseEvent>;
  let keydowns: Subject<KeyboardEvent>;

  async function setup(data: EmployeeFormDialogData): Promise<void> {
    backdropClicks = new Subject<MouseEvent>();
    keydowns = new Subject<KeyboardEvent>();
    dialogRef = jasmine.createSpyObj('MatDialogRef', ['close', 'backdropClick', 'keydownEvents', 'getState']);
    dialogRef.getState.and.returnValue(MatDialogState.OPEN);
    dialogRef.backdropClick.and.returnValue(backdropClicks);
    dialogRef.keydownEvents.and.returnValue(keydowns);
    await TestBed.configureTestingModule({
      imports: [EmployeeFormDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    snack = spyOn(TestBed.inject(MatSnackBar), 'open').and.stub();
    fixture = TestBed.createComponent(EmployeeFormDialogComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);
    await fixture.whenStable();
  }

  afterEach(() => {
    jasmine.clock().uninstall();
    http.verify();
  });

  describe('create mode', () => {
    beforeEach(() => setup({ employee: null }));

    it('FR-2.1: posts a CreateEmployeeRequest — no version, hire date as the calendar day picked', () => {
      component.form.patchValue({
        employeeCode: 'E10001', firstName: 'Alan', lastName: 'Turing', email: 'alan.turing@acme.com',
        gender: 'MALE', hireDate: new Date(2026, 10, 2), employmentType: 'FULL_TIME', fteRatio: 1,
        departmentId: 1, jobRoleId: 2, locationId: 1,
      });
      component.submit();

      const req = http.expectOne('/api/v1/employees');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        employeeCode: 'E10001', firstName: 'Alan', lastName: 'Turing', email: 'alan.turing@acme.com',
        gender: 'MALE', hireDate: '2026-11-02', employmentType: 'FULL_TIME', fteRatio: 1,
        departmentId: 1, jobRoleId: 2, locationId: 1, managerId: null,
      });
      expect('version' in req.request.body).toBeFalse();
      req.flush(detail(), { status: 201, statusText: 'Created' });
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('FR-3.6 / FR-2.1: a typed hire date of 2026-11-02 is that calendar day and is posted as 2026-11-02', async () => {
      const input = fixture.nativeElement.querySelector('#emp-hire-date') as HTMLInputElement;
      input.value = '2026-11-02';
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      expect((component.form.controls.hireDate.value as Date).getTime()).toBe(new Date(2026, 10, 2).getTime());

      component.form.patchValue({
        employeeCode: 'E10001', firstName: 'Alan', lastName: 'Turing', email: 'alan.turing@acme.com',
        employmentType: 'FULL_TIME', fteRatio: 1, departmentId: 1, jobRoleId: 2, locationId: 1,
      });
      component.submit();

      const req = http.expectOne('/api/v1/employees');
      expect(req.request.body.hireDate).toBe('2026-11-02');
      req.flush(detail(), { status: 201, statusText: 'Created' });
    });

    it('FR-2.1: required fields block submission', () => {
      component.form.patchValue({ firstName: 'Alan' });
      expect(component.form.invalid).toBeTrue();
      component.submit();
      http.expectNone('/api/v1/employees');
    });

    it('FR-2.5: the manager is picked by searching the directory (server-side, ten matches), never by typing an id', () => {
      jasmine.clock().install();
      component.managerCtrl.setValue('hop');
      jasmine.clock().tick(300);

      const search = http.expectOne(r => r.url === '/api/v1/employees');
      expect(search.request.params.get('q')).toBe('hop');
      expect(search.request.params.get('size')).toBe('10');
      search.flush(page([listItem({ id: 7, firstName: 'Grace', lastName: 'Hopper', employeeCode: 'E00007' })]));

      expect(component.managerOptions().map(o => o.label)).toEqual(['Grace Hopper (E00007)']);
      component.managerCtrl.setValue(component.managerOptions()[0]);
      expect(component.displayManager(component.managerCtrl.value)).toBe('Grace Hopper (E00007)');

      component.form.patchValue({
        employeeCode: 'E10001', firstName: 'Alan', lastName: 'Turing', email: 'alan.turing@acme.com',
        hireDate: new Date(2026, 10, 2), employmentType: 'FULL_TIME', fteRatio: 1,
        departmentId: 1, jobRoleId: 2, locationId: 1,
      });
      component.submit();
      const req = http.expectOne(r => r.url === '/api/v1/employees' && r.method === 'POST');
      expect(req.request.body.managerId).toBe(7);
      req.flush(detail(), { status: 201, statusText: 'Created' });
    });

    it('FR-2.1: a duplicate code is reported from the server\'s problem detail and the dialog stays open', async () => {
      component.form.patchValue({
        employeeCode: 'E00042', firstName: 'Alan', lastName: 'Turing', email: 'alan.turing@acme.com',
        hireDate: new Date(2026, 10, 2), employmentType: 'FULL_TIME', fteRatio: 1,
        departmentId: 1, jobRoleId: 2, locationId: 1,
      });
      component.submit();
      http.expectOne('/api/v1/employees').flush(
        { code: 'DUPLICATE_EMPLOYEE_CODE', detail: 'An employee with that code already exists' },
        { status: 409, statusText: 'Conflict' });
      await fixture.whenStable();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.saving()).toBeFalse();
      expect(snack).toHaveBeenCalledWith('An employee with that code already exists', 'Close', jasmine.anything());
    });
  });

  describe('edit mode', () => {
    beforeEach(() => setup({ employee: detail() }));

    it('ADR-0010: code and hire date are shown but not editable; the rest is prefilled', () => {
      expect(fixture.nativeElement.querySelector('#emp-code')).toBeNull();
      expect(fixture.nativeElement.querySelector('#emp-hire-date')).toBeNull();
      expect(fixture.nativeElement.textContent).toContain('E00042');
      expect(component.form.value.firstName).toBe('Ada');
      expect(component.form.value.departmentId).toBe(1);
      expect(component.form.value.employmentStatus).toBe('ACTIVE');
      expect(component.displayManager(component.managerCtrl.value)).toBe('Grace Hopper (E00007)');
    });

    it('FR-2.6: the update carries the version that was read, so the server can refuse a stale write', () => {
      component.form.patchValue({ lastName: 'Lovelace', employmentStatus: 'ON_LEAVE' });
      component.submit();

      const req = http.expectOne('/api/v1/employees/42');
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({
        version: 3, firstName: 'Ada', lastName: 'Lovelace', email: 'ada.byron@acme.com', gender: 'FEMALE',
        employmentType: 'FULL_TIME', fteRatio: 1, departmentId: 1, jobRoleId: 2, locationId: 1,
        managerId: 7, employmentStatus: 'ON_LEAVE',
      });
      req.flush(detail({ version: 4 }));
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('FR-2.6: a concurrent edit (409 CONCURRENT_UPDATE) is reported as such and nothing is overwritten', async () => {
      component.form.patchValue({ lastName: 'Lovelace' });
      component.submit();
      http.expectOne('/api/v1/employees/42').flush(
        { code: 'CONCURRENT_UPDATE', detail: 'The employee was modified by another request' },
        { status: 409, statusText: 'Conflict' });
      await fixture.whenStable();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.conflict()).toBeTrue();
      expect(fixture.nativeElement.textContent).toContain('changed by someone else');
      expect((fixture.nativeElement.querySelector('#emp-submit') as HTMLButtonElement).disabled).toBeTrue();
    });

    // FR-2.6: after a 409 the caller must learn the version it holds is stale, however the user
    // dismisses the dialog, or it would reopen the form with the same stale version and 409 again.
    describe('after a concurrent edit (409)', () => {
      beforeEach(async () => {
        component.form.patchValue({ lastName: 'Lovelace' });
        component.submit();
        http.expectOne('/api/v1/employees/42').flush(
          { code: 'CONCURRENT_UPDATE', detail: 'The employee was modified by another request' },
          { status: 409, statusText: 'Conflict' });
        await fixture.whenStable();
      });

      it('FR-2.6: the Close button closes with "conflict" so the caller reloads', () => {
        const button = fixture.nativeElement.querySelector('#emp-cancel') as HTMLButtonElement;
        expect(button.textContent).toContain('Close');
        button.click();
        expect(dialogRef.close).toHaveBeenCalledOnceWith('conflict');
      });

      it('FR-2.6: Escape closes with "conflict"', () => {
        keydowns.next(new KeyboardEvent('keydown', { key: 'Escape' }));
        expect(dialogRef.close).toHaveBeenCalledOnceWith('conflict');
      });

      it('FR-2.6: a backdrop click closes with "conflict"', () => {
        backdropClicks.next(new MouseEvent('click'));
        expect(dialogRef.close).toHaveBeenCalledOnceWith('conflict');
      });

      it('FR-2.6: other keys do not close the dialog', () => {
        keydowns.next(new KeyboardEvent('keydown', { key: 'a' }));
        expect(dialogRef.close).not.toHaveBeenCalled();
      });
    });

    it('FR-2.6: cancel() does nothing once the dialog is no longer open', () => {
      dialogRef.getState.and.returnValue(MatDialogState.CLOSING);
      component.cancel();
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('FR-2.6: without a conflict, Cancel, Escape and backdrop all close with false (nothing to reload)', () => {
      (fixture.nativeElement.querySelector('#emp-cancel') as HTMLButtonElement).click();
      keydowns.next(new KeyboardEvent('keydown', { key: 'Escape' }));
      backdropClicks.next(new MouseEvent('click'));
      expect(dialogRef.close.calls.allArgs()).toEqual([[false], [false], [false]]);
    });

    // Without this check a half-typed name would be sent as managerId null and silently drop the manager.
    it('FR-2.5: typing a manager name without picking one from the list makes the form invalid and blocks the save', () => {
      jasmine.clock().install();
      const input = fixture.nativeElement.querySelector('#emp-manager') as HTMLInputElement;
      input.value = 'Grace';
      input.dispatchEvent(new Event('input'));
      jasmine.clock().tick(300);
      http.expectOne(r => r.url === '/api/v1/employees' && r.params.get('q') === 'Grace')
        .flush(page([listItem({ id: 8, firstName: 'Grace', lastName: 'Murray', employeeCode: 'E00008' })]));

      expect(component.managerCtrl.hasError('pickManager')).toBeTrue();
      expect((fixture.nativeElement.querySelector('#emp-submit') as HTMLButtonElement).disabled).toBeTrue();
      component.submit();
      http.expectNone('/api/v1/employees/42');
    });

    it('FR-2.5: picking a listed manager clears the error and the save carries the id of the picked manager', async () => {
      component.managerCtrl.setValue('Grace');
      expect(component.managerCtrl.hasError('pickManager')).toBeTrue();
      component.submit();
      http.expectNone('/api/v1/employees/42');

      component.managerCtrl.setValue({ id: 8, label: 'Grace Murray (E00008)' });
      await fixture.whenStable();

      expect(component.managerCtrl.hasError('pickManager')).toBeFalse();
      expect((fixture.nativeElement.querySelector('#emp-submit') as HTMLButtonElement).disabled).toBeFalse();
      component.submit();
      const req = http.expectOne('/api/v1/employees/42');
      expect(req.request.body.managerId).toBe(8);
      req.flush(detail({ version: 4 }));
    });

    it('FR-2.5: clearing the manager sends managerId null', () => {
      component.clearManager();
      component.submit();

      const req = http.expectOne('/api/v1/employees/42');
      expect(req.request.body.managerId).toBeNull();
      req.flush(detail());
    });
  });
});

// The specs above stub MatDialogRef. This one uses the real MatDialog so the claim "Escape and a
// backdrop click cannot bypass cancel()" is checked against Material's own close handling.
describe('EmployeeFormDialogComponent inside a real MatDialog', () => {
  let http: HttpTestingController;
  let dialog: MatDialog;

  async function openAndLoseTheRace(): Promise<{ result: () => unknown; closed: () => boolean }> {
    await TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    dialog = TestBed.inject(MatDialog);
    spyOn(TestBed.inject(MatSnackBar), 'open').and.stub();

    const ref = dialog.open(EmployeeFormDialogComponent, { data: { employee: detail() } });
    let closed = false;
    let result: unknown;
    ref.afterClosed().subscribe(r => { closed = true; result = r; });
    http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
    http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
    http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);

    ref.componentInstance.form.patchValue({ lastName: 'Lovelace' });
    ref.componentInstance.submit();
    http.expectOne('/api/v1/employees/42').flush(
      { code: 'CONCURRENT_UPDATE', detail: 'The employee was modified by another request' },
      { status: 409, statusText: 'Conflict' });
    return { result: () => result, closed: () => closed };
  }

  afterEach(() => {
    dialog.closeAll();
    http.verify();
  });

  it('FR-2.6: Escape after a 409 closes the real dialog with "conflict"', async () => {
    const outcome = await openAndLoseTheRace();

    document.querySelector('mat-dialog-container')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
    await TestBed.inject(ApplicationRef).whenStable();

    expect(outcome.closed()).toBeTrue();
    expect(outcome.result()).toBe('conflict');
  });

  it('FR-2.6: a backdrop click after a 409 closes the real dialog with "conflict"', async () => {
    const outcome = await openAndLoseTheRace();

    (document.querySelector('.cdk-overlay-backdrop') as HTMLElement).click();
    await TestBed.inject(ApplicationRef).whenStable();

    expect(outcome.closed()).toBeTrue();
    expect(outcome.result()).toBe('conflict');
  });

  // MatDialogRef.close() has no state guard: a second close() while the exit animation is still
  // running overwrites the result of the first. After a successful save the caller must still
  // see `true`, or it skips the reload and keeps a stale version.
  describe('dismissing while the exit animation of a successful save is still running', () => {
    async function saveSuccessfully(): Promise<{
      results: unknown[]; ref: MatDialogRef<EmployeeFormDialogComponent>; closed: Promise<void>;
    }> {
      await TestBed.configureTestingModule({
        providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
      }).compileComponents();
      http = TestBed.inject(HttpTestingController);
      dialog = TestBed.inject(MatDialog);
      spyOn(TestBed.inject(MatSnackBar), 'open').and.stub();

      const ref = dialog.open(EmployeeFormDialogComponent, { data: { employee: detail() } });
      const results: unknown[] = [];
      const closed = new Promise<void>(resolve => ref.afterClosed().subscribe(r => { results.push(r); resolve(); }));
      http.expectOne('/api/v1/reference/departments').flush(DEPARTMENTS);
      http.expectOne('/api/v1/reference/locations').flush(LOCATIONS);
      http.expectOne('/api/v1/reference/job-roles').flush(JOB_ROLES);

      ref.componentInstance.form.patchValue({ lastName: 'Lovelace' });
      ref.componentInstance.submit();
      http.expectOne('/api/v1/employees/42').flush(detail({ version: 4 }));
      return { results, ref, closed };
    }

    it('FR-2.6: Escape during the exit animation does not replace the saved result', async () => {
      const { results, ref, closed } = await saveSuccessfully();
      expect(ref.getState()).toBe(MatDialogState.CLOSING);

      document.querySelector('mat-dialog-container')!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
      await closed;

      expect(results).toEqual([true]);
    });

    it('FR-2.6: cancel() during the exit animation does not replace the saved result', async () => {
      const { results, ref, closed } = await saveSuccessfully();

      ref.componentInstance.cancel();
      await closed;

      expect(results).toEqual([true]);
    });
  });
});
