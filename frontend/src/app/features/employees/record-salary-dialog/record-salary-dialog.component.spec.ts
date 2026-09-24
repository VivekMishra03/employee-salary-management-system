import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RecordSalaryDialogComponent } from './record-salary-dialog.component';
import { salary } from '../../../../testing/fixtures';

describe('RecordSalaryDialogComponent', () => {
  let fixture: ComponentFixture<RecordSalaryDialogComponent>;
  let component: RecordSalaryDialogComponent;
  let http: HttpTestingController;
  let dialogRef: jasmine.SpyObj<MatDialogRef<RecordSalaryDialogComponent>>;
  let snack: jasmine.Spy;

  const validForm = () => ({
    effectiveFrom: new Date(2026, 9, 1),   // local midnight, 1 October 2026
    baseAmount: 98500.5,
    payFrequency: 'ANNUAL' as const,
    targetBonusPct: 12.5,
    changeReason: 'PROMOTION' as const,
    notes: 'Promoted to L5',
  });

  beforeEach(async () => {
    dialogRef = jasmine.createSpyObj('MatDialogRef', ['close']);
    await TestBed.configureTestingModule({
      imports: [RecordSalaryDialogComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { employeeId: 42, employeeName: 'Ada Byron' } },
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    snack = spyOn(TestBed.inject(MatSnackBar), 'open').and.stub();
    fixture = TestBed.createComponent(RecordSalaryDialogComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('FR-3.6: the effective date is sent as the calendar day picked', () => {
    component.form.setValue(validForm());
    component.submit();

    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      effectiveFrom: '2026-10-01',
      baseAmount: 98500.5,
      payFrequency: 'ANNUAL',
      targetBonusPct: 12.5,
      changeReason: 'PROMOTION',
      notes: 'Promoted to L5',
    });
    req.flush(salary(), { status: 201, statusText: 'Created' });
  });

  // Typed, not picked: the datepicker input parses text through the DateAdapter (ISO date-only text
  // is read as UTC midnight by the native parser, which is the previous day west of UTC).
  it('FR-3.6: a typed 2026-10-01 is the 1 October calendar day and is posted as 2026-10-01', async () => {
    const input = fixture.nativeElement.querySelector('#sal-effective-from') as HTMLInputElement;
    input.value = '2026-10-01';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    const typed = component.form.controls.effectiveFrom.value as Date;
    expect(typed.getTime()).toBe(new Date(2026, 9, 1).getTime());

    component.form.patchValue({
      baseAmount: 98500.5, targetBonusPct: 12.5, changeReason: 'PROMOTION', notes: 'Promoted to L5',
    });
    component.submit();

    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.body.effectiveFrom).toBe('2026-10-01');
    req.flush(salary(), { status: 201, statusText: 'Created' });
  });

  it('FR-3.6: a back-dated effective date is a valid form and is posted unchanged', () => {
    component.form.setValue({ ...validForm(), effectiveFrom: new Date(2020, 0, 15) });
    expect(component.form.valid).toBeTrue();
    component.submit();

    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.body.effectiveFrom).toBe('2020-01-15');
    req.flush(salary(), { status: 201, statusText: 'Created' });
  });

  it('FR-3.6: a future effective date is a valid form and is posted unchanged', () => {
    component.form.setValue({ ...validForm(), effectiveFrom: new Date(2099, 0, 15) });
    expect(component.form.valid).toBeTrue();
    component.submit();

    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.body.effectiveFrom).toBe('2099-01-15');
    req.flush(salary(), { status: 201, statusText: 'Created' });
  });

  it('FR-3.3: reason is mandatory and the amount must be positive money with at most two decimals', () => {
    component.form.setValue({ ...validForm(), changeReason: null });
    expect(component.form.invalid).toBeTrue();

    component.form.setValue({ ...validForm(), baseAmount: 0 });
    expect(component.form.invalid).toBeTrue();

    component.form.setValue({ ...validForm(), baseAmount: 1000.005 });
    expect(component.form.controls.baseAmount.hasError('twoDecimals')).toBeTrue();
    component.submit();
    http.expectNone('/api/v1/employees/42/salaries');

    component.form.setValue(validForm());
    expect(component.form.valid).toBeTrue();
  });

  it('FR-3.2 (UI): a saved change closes the dialog with true so the caller reloads', () => {
    component.form.setValue(validForm());
    component.submit();
    http.expectOne('/api/v1/employees/42/salaries').flush(salary(), { status: 201, statusText: 'Created' });

    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('FR-3.2 (UI): a rejected change shows the server\'s reason, stays open and lets the user retry', async () => {
    component.form.setValue(validForm());
    component.submit();
    expect(component.saving()).toBeTrue();

    http.expectOne('/api/v1/employees/42/salaries').flush(
      { code: 'EFFECTIVE_BEFORE_HIRE', detail: 'Effective date is before the hire date',
        errors: [{ field: 'effectiveFrom', message: 'Effective date is before the hire date' }] },
      { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.saving()).toBeFalse();
    expect(snack).toHaveBeenCalledWith('effectiveFrom: Effective date is before the hire date', 'Close', jasmine.anything());
  });

  it('FR-3.3: empty notes are sent as null, not an empty string', () => {
    component.form.setValue({ ...validForm(), notes: '' });
    component.submit();

    const req = http.expectOne('/api/v1/employees/42/salaries');
    expect(req.request.body.notes).toBeNull();
    req.flush(salary(), { status: 201, statusText: 'Created' });
  });
});
