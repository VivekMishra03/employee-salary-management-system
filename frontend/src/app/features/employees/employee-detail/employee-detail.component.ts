import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemMessage } from '../../../core/http/problem';
import { EmployeeDetail, SalarySummary } from '../../../core/models/employee.model';
import { MoneyPipe } from '../../../core/pipes/money.pipe';
import { EmployeeService } from '../../../core/services/employee.service';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { EmployeeFormDialogComponent, EmployeeFormDialogData } from '../employee-form-dialog/employee-form-dialog.component';
import { RecordSalaryDialogComponent, RecordSalaryDialogData } from '../record-salary-dialog/record-salary-dialog.component';

/** FR-2.5: the full employee view. FR-3.1: the complete salary history, newest first as delivered. */
@Component({
  selector: 'app-employee-detail',
  imports: [
    DatePipe,
    RouterLink,
    MoneyPipe,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
  ],
  // Tab bodies attach their content on a CSS transition event; without animations they attach at once.
  providers: [{ provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } }],
  templateUrl: './employee-detail.component.html',
  styleUrl: './employee-detail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmployeeDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly employeeService = inject(EmployeeService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  private readonly id = Number(this.route.snapshot.paramMap.get('id'));

  readonly employee = signal<EmployeeDetail | null>(null);
  readonly loading = signal(true);
  /** The message to show instead of the profile, or null when the employee loaded. */
  readonly error = signal<string | null>(null);

  /** ADR-0010: a terminated employee is a closed record; only salary history may still be corrected. */
  protected readonly terminated = computed(() => this.employee()?.employmentStatus === 'TERMINATED');

  protected readonly salaryColumns = [
    'effectiveFrom', 'effectiveTo', 'baseAmount', 'currencyCode', 'payFrequency',
    'annualisedAmountBaseCcy', 'targetBonusPct', 'changeReason', 'notes',
  ];

  constructor() {
    this.load();
  }

  /** ADR-0011: a same-day correction leaves a zero-length record behind; it is shown, not hidden. */
  protected isSuperseded(record: SalarySummary): boolean {
    return record.effectiveFrom === record.effectiveTo;
  }

  protected goBack(): void {
    this.router.navigate(['/employees']);
  }

  protected openEdit(employee: EmployeeDetail): void {
    const data: EmployeeFormDialogData = { employee };
    this.dialog.open(EmployeeFormDialogComponent, { width: '640px', data })
      .afterClosed()
      // Reload on anything but a plain cancel: a 'conflict' result means the version held here is stale.
      .subscribe(result => {
        if (result !== false) {
          this.load();
        }
      });
  }

  protected openRecordSalary(employee: EmployeeDetail): void {
    const data: RecordSalaryDialogData = {
      employeeId: employee.id,
      employeeName: `${employee.firstName} ${employee.lastName}`,
    };
    this.dialog.open(RecordSalaryDialogComponent, { width: '520px', data })
      .afterClosed()
      .subscribe(saved => this.reloadIf(saved));
  }

  protected confirmDeactivate(employee: EmployeeDetail): void {
    const data: ConfirmDialogData = {
      title: 'Deactivate employee',
      message: `${employee.firstName} ${employee.lastName} will be marked TERMINATED and can no longer be edited. Continue?`,
      confirmLabel: 'Deactivate',
    };
    this.dialog.open(ConfirmDialogComponent, { data })
      .afterClosed()
      .subscribe(confirmed => {
        if (confirmed) {
          this.deactivate(employee);
        }
      });
  }

  private deactivate(employee: EmployeeDetail): void {
    this.employeeService.deactivate(employee.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.snackBar.open('Employee deactivated.', 'Close', { duration: 3000 });
        this.load();
      },
      error: err => this.snackBar.open(problemMessage(err, 'Failed to deactivate employee.'), 'Close', { duration: 5000 }),
    });
  }

  private reloadIf(saved: unknown): void {
    if (saved) {
      this.load();
    }
  }

  private load(): void {
    this.employeeService.get(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: employee => {
        this.employee.set(employee);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        if (this.employee()) {
          // A failed refresh must not blank a profile the user is already looking at.
          this.snackBar.open(problemMessage(err, 'Could not refresh the employee.'), 'Close', { duration: 5000 });
          return;
        }
        this.error.set(err.status === 404 ? 'Employee not found' : problemMessage(err, 'Could not load the employee.'));
      },
    });
  }
}
