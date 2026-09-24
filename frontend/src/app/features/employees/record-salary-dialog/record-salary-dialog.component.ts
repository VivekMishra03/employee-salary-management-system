import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { DateAdapter, MAT_DATE_FORMATS, MAT_NATIVE_DATE_FORMATS } from '@angular/material/core';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemMessage } from '../../../core/http/problem';
import { CHANGE_REASONS, ChangeReason, PAY_FREQUENCIES, PayFrequency, RecordSalaryRequest } from '../../../core/models/employee.model';
import { SalaryService } from '../../../core/services/salary.service';
import { toIsoDate } from '../../../core/util/date.util';
import { IsoDateAdapter } from '../../../core/util/iso-date-adapter';
import { maxDecimals } from '../../../shared/validators/max-decimals';

export interface RecordSalaryDialogData {
  employeeId: number;
  employeeName: string;
}

/** FR-3.2 – FR-3.6: record a salary change. The currency is the employee's, so it is not a field (FR-3.4). */
@Component({
  selector: 'app-record-salary-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatButtonModule,
  ],
  providers: [
    { provide: DateAdapter, useClass: IsoDateAdapter },
    { provide: MAT_DATE_FORMATS, useValue: MAT_NATIVE_DATE_FORMATS },
  ],
  templateUrl: './record-salary-dialog.component.html',
  styleUrl: './record-salary-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecordSalaryDialogComponent {
  protected readonly data = inject<RecordSalaryDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<RecordSalaryDialogComponent>>(MatDialogRef);
  private readonly salaryService = inject(SalaryService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly payFrequencies = PAY_FREQUENCIES;
  protected readonly changeReasons = CHANGE_REASONS;

  readonly saving = signal(false);

  // Bounds mirror RecordSalaryRequest.java; the server stays the authority.
  readonly form = new FormGroup({
    effectiveFrom: new FormControl<Date | null>(null, [Validators.required]),
    baseAmount: new FormControl<number | null>(null, [
      Validators.required, Validators.min(0.01), maxDecimals(2, 'twoDecimals'),
    ]),
    payFrequency: new FormControl<PayFrequency>('ANNUAL', { nonNullable: true, validators: [Validators.required] }),
    targetBonusPct: new FormControl<number | null>(0, [
      Validators.min(0), Validators.max(999.99), maxDecimals(2, 'twoDecimals'),
    ]),
    changeReason: new FormControl<ChangeReason | null>(null, [Validators.required]),
    notes: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(2000)] }),
  });

  submit(): void {
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);

    const value = this.form.getRawValue();
    const request: RecordSalaryRequest = {
      effectiveFrom: toIsoDate(value.effectiveFrom!),
      baseAmount: value.baseAmount!,
      payFrequency: value.payFrequency,
      targetBonusPct: value.targetBonusPct ?? 0,
      changeReason: value.changeReason!,
      notes: value.notes.trim() ? value.notes : null,
    };

    this.salaryService.record(this.data.employeeId, request).subscribe({
      next: () => {
        this.snackBar.open('Salary change recorded.', 'Close', { duration: 3000 });
        this.dialogRef.close(true);
      },
      error: err => {
        this.saving.set(false);
        this.snackBar.open(problemMessage(err, 'Failed to record salary change.'), 'Close', { duration: 5000 });
      },
    });
  }

  cancel(): void {
    this.dialogRef.close(false);
  }
}
