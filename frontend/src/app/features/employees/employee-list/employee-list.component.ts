import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { EMPTY, Observable, Subject, catchError, distinctUntilChanged, map, merge, of, skip, startWith, switchMap, tap } from 'rxjs';
import { EmployeeListItem, EmploymentStatus, EmploymentType, Page } from '../../../core/models/employee.model';
import { problemMessage } from '../../../core/http/problem';
import { debounceByTimer } from '../../../core/util/debounce';
import { EmployeeService } from '../../../core/services/employee.service';
import { ReferenceService } from '../../../core/services/reference.service';
import { EmployeeFormDialogComponent, EmployeeFormDialogData } from '../employee-form-dialog/employee-form-dialog.component';

/**
 * FR-2.2 – FR-2.4: paginated, sorted, filtered employee directory. Paging, sorting and filtering
 * all happen on the server; the browser only ever holds the current page of a 10,000-row table.
 */
@Component({
  selector: 'app-employee-list',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatChipsModule,
  ],
  templateUrl: './employee-list.component.html',
  styleUrl: './employee-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmployeeListComponent {
  private readonly employeeService = inject(EmployeeService);
  private readonly referenceService = inject(ReferenceService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  // The sortable headers are exactly the API's sort whitelist (EmployeeService.SORTABLE).
  protected readonly displayedColumns = [
    'employeeCode', 'name', 'email', 'departmentName', 'jobTitle', 'jobLevel', 'location',
    'hireDate', 'employmentStatus', 'employmentType', 'actions',
  ];
  protected readonly pageSizeOptions = [25, 50, 100];

  protected readonly employees = signal<EmployeeListItem[]>([]);
  readonly totalElements = signal(0);
  readonly loading = signal(false);
  protected readonly pageIndex = signal(0);
  protected readonly pageSize = signal(25);
  private readonly sort = signal<Sort>({ active: 'lastName', direction: 'asc' });

  readonly searchCtrl = new FormControl('', { nonNullable: true });
  readonly departmentCtrl = new FormControl<number | null>(null);
  readonly countryCtrl = new FormControl<string | null>(null);
  readonly statusCtrl = new FormControl<EmploymentStatus | null>(null);
  readonly typeCtrl = new FormControl<EmploymentType | null>(null);
  readonly levelCtrl = new FormControl<string | null>(null);

  readonly departments = toSignal(
    this.referenceService.getDepartments().pipe(catchError(() => of([]))), { initialValue: [] });
  private readonly locations = toSignal(
    this.referenceService.getLocations().pipe(catchError(() => of([]))), { initialValue: [] });
  private readonly jobRoles = toSignal(
    this.referenceService.getJobRoles().pipe(catchError(() => of([]))), { initialValue: [] });

  /** Distinct countries by name: there is one location row per city, so the raw list repeats them. */
  readonly countries = computed(() => {
    const byCode = new Map(this.locations().map(l => [l.countryCode, { code: l.countryCode, name: l.countryName }]));
    return [...byCode.values()].sort((a, b) => a.name.localeCompare(b.name));
  });
  readonly jobLevels = computed(() => [...new Set(this.jobRoles().map(r => r.jobLevel))].sort());

  private readonly reload$ = new Subject<void>();

  constructor() {
    // switchMap: a slow response to an old query must never overwrite the page of a newer one.
    this.reload$.pipe(
      tap(() => this.loading.set(true)),
      switchMap(() => this.query()),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(page => {
      this.employees.set(page.content);
      this.totalElements.set(page.totalElements);
      this.loading.set(false);
    });

    // FR-2.3: debounced so that each keystroke does not cost a query. Compared trimmed, because the
    // query is sent trimmed: 'ada' and 'ada ' are the same search. startWith('') makes the initial
    // empty search the baseline, so typing only spaces does not re-issue it.
    this.searchCtrl.valueChanges.pipe(
      debounceByTimer(400),
      map(text => text.trim()),
      startWith(''),
      distinctUntilChanged(),
      skip(1),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(() => this.restart());

    // FR-2.4: dropdown filters apply immediately.
    merge(
      this.departmentCtrl.valueChanges,
      this.countryCtrl.valueChanges,
      this.statusCtrl.valueChanges,
      this.typeCtrl.valueChanges,
      this.levelCtrl.valueChanges,
    ).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.restart());

    this.reload$.next();
  }

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.reload$.next();
  }

  onSort(sort: Sort): void {
    this.sort.set(sort.direction ? sort : { active: 'lastName', direction: 'asc' });
    this.restart();
  }

  protected open(employee: EmployeeListItem): void {
    this.router.navigate(['/employees', employee.id]);
  }

  protected openCreate(): void {
    const data: EmployeeFormDialogData = { employee: null };
    this.dialog.open(EmployeeFormDialogComponent, { width: '640px', data })
      .afterClosed()
      .subscribe(created => {
        if (created) {
          this.reload$.next();
        }
      });
  }

  /** A changed query invalidates the current page number, so it starts again from the first page. */
  private restart(): void {
    this.pageIndex.set(0);
    this.reload$.next();
  }

  private query(): Observable<Page<EmployeeListItem>> {
    const { active, direction } = this.sort();
    return this.employeeService.search(
      {
        q: this.searchCtrl.value.trim() || undefined,
        departmentId: this.departmentCtrl.value ?? undefined,
        countryCode: this.countryCtrl.value ?? undefined,
        status: this.statusCtrl.value ?? undefined,
        employmentType: this.typeCtrl.value ?? undefined,
        jobLevel: this.levelCtrl.value ?? undefined,
      },
      this.pageIndex(),
      this.pageSize(),
      `${active},${direction}`,
    ).pipe(
      catchError(err => {
        // Rows from the previous filters would read as the answer to the query that just failed.
        this.employees.set([]);
        this.totalElements.set(0);
        this.loading.set(false);
        this.snackBar.open(problemMessage(err, 'Could not load employees.'), 'Close', { duration: 5000 });
        return EMPTY;
      }),
    );
  }
}
