import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef, MatDialogState } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { DateAdapter, MAT_DATE_FORMATS, MAT_NATIVE_DATE_FORMATS } from '@angular/material/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, catchError, filter, map, merge, of, switchMap } from 'rxjs';
import { problemCode, problemMessage } from '../../../core/http/problem';
import {
  CreateEmployeeRequest,
  EMPLOYMENT_TYPES,
  EmployeeDetail,
  EmployeeListItem,
  EmploymentStatus,
  EmploymentType,
  GENDERS,
  Gender,
  UpdateEmployeeRequest,
} from '../../../core/models/employee.model';
import { EmployeeService } from '../../../core/services/employee.service';
import { ReferenceService } from '../../../core/services/reference.service';
import { debounceByTimer } from '../../../core/util/debounce';
import { toIsoDate } from '../../../core/util/date.util';
import { IsoDateAdapter } from '../../../core/util/iso-date-adapter';
import { maxDecimals } from '../../../shared/validators/max-decimals';

/** `employee: null` opens the form in create mode (FR-2.1); a loaded record opens it for editing. */
export interface EmployeeFormDialogData {
  employee: EmployeeDetail | null;
}

export interface ManagerOption {
  id: number;
  label: string;
}

/** A typed-but-unselected manager would silently become "no manager", so it is refused instead. */
function managerMustBePicked(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  return typeof value === 'string' && value.trim() !== '' ? { pickManager: true } : null;
}

/**
 * FR-2.1: create or edit an employee. FR-2.6: an edit carries the version it read, so a concurrent
 * change is refused by the server (409) rather than overwritten. FR-2.5: the manager is found by
 * searching the directory server-side, never by typing an id. ADR-0010: code and hire date are
 * immutable after creation, and TERMINATED is reachable only through deactivation.
 */
@Component({
  selector: 'app-employee-form-dialog',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatAutocompleteModule,
    MatDatepickerModule,
    MatButtonModule,
    MatIconModule,
  ],
  providers: [
    { provide: DateAdapter, useClass: IsoDateAdapter },
    { provide: MAT_DATE_FORMATS, useValue: MAT_NATIVE_DATE_FORMATS },
  ],
  templateUrl: './employee-form-dialog.component.html',
  styleUrl: './employee-form-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmployeeFormDialogComponent {
  private readonly data = inject<EmployeeFormDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<EmployeeFormDialogComponent>>(MatDialogRef);
  private readonly employeeService = inject(EmployeeService);
  private readonly referenceService = inject(ReferenceService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  /** The record being edited, or null when creating. */
  protected readonly employee = this.data.employee;

  protected readonly genders = GENDERS;
  protected readonly employmentTypes = EMPLOYMENT_TYPES;
  protected readonly editableStatuses: readonly EmploymentStatus[] = ['ACTIVE', 'ON_LEAVE'];

  protected readonly departments = toSignal(
    this.referenceService.getDepartments().pipe(catchError(() => of([]))), { initialValue: [] });
  protected readonly jobRoles = toSignal(
    this.referenceService.getJobRoles().pipe(catchError(() => of([]))), { initialValue: [] });
  protected readonly locations = toSignal(
    this.referenceService.getLocations().pipe(catchError(() => of([]))), { initialValue: [] });

  readonly saving = signal(false);
  readonly conflict = signal(false);
  readonly managerOptions = signal<ManagerOption[]>([]);

  readonly form = new FormGroup({
    employeeCode: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(20)] }),
    firstName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    lastName: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    email: new FormControl('', {
      nonNullable: true, validators: [Validators.required, Validators.email, Validators.maxLength(255)],
    }),
    gender: new FormControl<Gender | null>(null),
    hireDate: new FormControl<Date | null>(null, [Validators.required]),
    employmentType: new FormControl<EmploymentType | null>(null, [Validators.required]),
    fteRatio: new FormControl<number | null>(1, [
      Validators.required, Validators.min(0.001), Validators.max(1), maxDecimals(3, 'threeDecimals'),
    ]),
    departmentId: new FormControl<number | null>(null, [Validators.required]),
    jobRoleId: new FormControl<number | null>(null, [Validators.required]),
    locationId: new FormControl<number | null>(null, [Validators.required]),
    employmentStatus: new FormControl<EmploymentStatus>('ACTIVE', { nonNullable: true }),
  });

  readonly managerCtrl = new FormControl<string | ManagerOption | null>(null, [managerMustBePicked]);

  constructor() {
    const employee = this.employee;
    if (employee) {
      // Immutable after creation (ADR-0010): shown as text, and out of the form's value and validity.
      this.form.controls.employeeCode.disable();
      this.form.controls.hireDate.disable();
      this.form.patchValue({
        firstName: employee.firstName,
        lastName: employee.lastName,
        email: employee.email,
        gender: employee.gender,
        employmentType: employee.employmentType,
        fteRatio: employee.fteRatio,
        departmentId: employee.departmentId,
        jobRoleId: employee.jobRoleId,
        locationId: employee.locationId,
        employmentStatus: employee.employmentStatus,
      });
      if (employee.manager) {
        this.managerCtrl.setValue(
          { id: employee.manager.id, label: `${employee.manager.fullName} (${employee.manager.employeeCode})` },
          { emitEvent: false },
        );
      }
    } else {
      this.form.controls.employmentStatus.disable();
    }

    // FR-2.6: the dialog is only ever closed through cancel(), so that Escape and a backdrop click
    // report a conflict exactly as the Close button does. Left to Material they would close with
    // undefined and the caller would keep the stale version it just lost the race with.
    this.dialogRef.disableClose = true;
    merge(
      this.dialogRef.backdropClick(),
      this.dialogRef.keydownEvents().pipe(filter(event => event.key === 'Escape')),
    ).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.cancel());

    this.managerCtrl.valueChanges.pipe(
      filter((value): value is string => typeof value === 'string'),
      debounceByTimer(300),
      switchMap(text => this.searchManagers(text.trim())),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(options => this.managerOptions.set(options));
  }

  displayManager(value: string | ManagerOption | null): string {
    if (value === null) {
      return '';
    }
    return typeof value === 'string' ? value : value.label;
  }

  clearManager(): void {
    this.managerCtrl.setValue(null);
    this.managerOptions.set([]);
  }

  submit(): void {
    if (this.form.invalid || this.managerCtrl.invalid || this.saving() || this.conflict()) {
      return;
    }
    this.saving.set(true);

    const request$: Observable<EmployeeDetail> = this.employee
      ? this.employeeService.update(this.employee.id, this.updateRequest(this.employee.version))
      : this.employeeService.create(this.createRequest());

    request$.subscribe({
      next: () => {
        this.snackBar.open(this.employee ? 'Employee updated.' : 'Employee created.', 'Close', { duration: 3000 });
        this.dialogRef.close(true);
      },
      error: err => {
        this.saving.set(false);
        if (problemCode(err) === 'CONCURRENT_UPDATE') {
          this.conflict.set(true);
          return;
        }
        this.snackBar.open(problemMessage(err, 'Failed to save employee.'), 'Close', { duration: 5000 });
      },
    });
  }

  /**
   * Closes with 'conflict' after a lost update so the caller reloads, otherwise with false. Ignored
   * once the dialog is already closing: MatDialogRef.close() has no state guard, so a second call
   * during the exit animation would overwrite the `true` of a successful save and the caller would
   * skip its reload.
   */
  cancel(): void {
    if (this.dialogRef.getState() !== MatDialogState.OPEN) {
      return;
    }
    this.dialogRef.close(this.conflict() ? 'conflict' : false);
  }

  private searchManagers(text: string): Observable<ManagerOption[]> {
    if (!text) {
      return of([]);
    }
    return this.employeeService.search({ q: text }, 0, 10, 'lastName,asc').pipe(
      map(page => page.content.map(toManagerOption)),
      catchError(() => of([])),
    );
  }

  private selectedManagerId(): number | null {
    const value = this.managerCtrl.value;
    return value !== null && typeof value === 'object' ? value.id : null;
  }

  private createRequest(): CreateEmployeeRequest {
    const v = this.form.getRawValue();
    return {
      employeeCode: v.employeeCode.trim(),
      firstName: v.firstName.trim(),
      lastName: v.lastName.trim(),
      email: v.email.trim(),
      gender: v.gender,
      hireDate: toIsoDate(v.hireDate!),
      employmentType: v.employmentType!,
      fteRatio: v.fteRatio!,
      departmentId: v.departmentId!,
      jobRoleId: v.jobRoleId!,
      locationId: v.locationId!,
      managerId: this.selectedManagerId(),
    };
  }

  private updateRequest(version: number): UpdateEmployeeRequest {
    const v = this.form.getRawValue();
    return {
      version,
      firstName: v.firstName.trim(),
      lastName: v.lastName.trim(),
      email: v.email.trim(),
      gender: v.gender,
      employmentType: v.employmentType!,
      fteRatio: v.fteRatio!,
      departmentId: v.departmentId!,
      jobRoleId: v.jobRoleId!,
      locationId: v.locationId!,
      managerId: this.selectedManagerId(),
      employmentStatus: v.employmentStatus,
    };
  }
}

function toManagerOption(e: EmployeeListItem): ManagerOption {
  return { id: e.id, label: `${e.firstName} ${e.lastName} (${e.employeeCode})` };
}
