import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, distinctUntilChanged, map, merge, of, skip, startWith } from 'rxjs';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { EmploymentStatus, EmploymentType } from '../../../core/models/employee.model';
import { ReferenceService } from '../../../core/services/reference.service';
import { debounceByTimer } from '../../../core/util/debounce';

const FILTER_KEYS = ['q', 'departmentId', 'countryCode', 'status', 'employmentType', 'jobLevel'] as const;

const DEFAULT_STATUS = 'DEFAULT';

const sameFilter = (a: AnalyticsFilter, b: AnalyticsFilter): boolean => FILTER_KEYS.every(k => a[k] === b[k]);

/**
 * FR-4.7: the same six filters as the employee directory (FR-2.3, FR-2.4), emitted as one
 * AnalyticsFilter. The status control has no "All": leaving it on its default sends no status, which
 * the API reads as "active and on leave" (ADR-0013), so Terminated is an explicit choice.
 */
@Component({
  selector: 'app-analytics-filters',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  templateUrl: './analytics-filters.component.html',
  styleUrl: './analytics-filters.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsFiltersComponent {
  private readonly referenceService = inject(ReferenceService);

  /** Emitted only when the effective filter actually changes. */
  readonly filterChange = output<AnalyticsFilter>();

  readonly searchCtrl = new FormControl('', { nonNullable: true });
  readonly departmentCtrl = new FormControl<number | null>(null);
  readonly countryCtrl = new FormControl<string | null>(null);
  // A real value, not null: mat-select never treats a null-valued option as selected, so the default
  // option's label would not show in the field.
  readonly statusCtrl = new FormControl<EmploymentStatus | typeof DEFAULT_STATUS>(DEFAULT_STATUS, { nonNullable: true });
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

  constructor() {
    // The search waits for a pause in typing; the dropdowns apply at once. Comparing whole filters (not
    // just the search text) means 'ada ' after 'ada', or a dropdown change that already picked up the
    // typed text, does not cost a second reload of six panels.
    merge(
      this.searchCtrl.valueChanges.pipe(debounceByTimer(400)),
      this.departmentCtrl.valueChanges,
      this.countryCtrl.valueChanges,
      this.statusCtrl.valueChanges,
      this.typeCtrl.valueChanges,
      this.levelCtrl.valueChanges,
    ).pipe(
      map(() => this.current()),
      startWith(this.current()),
      distinctUntilChanged(sameFilter),
      skip(1),
      takeUntilDestroyed(),
    ).subscribe(filter => this.filterChange.emit(filter));
  }

  /** Only constraints that are set appear, so an empty filter is `{}`. */
  private current(): AnalyticsFilter {
    const filter: AnalyticsFilter = {};
    const q = this.searchCtrl.value.trim();
    if (q) {
      filter.q = q;
    }
    if (this.departmentCtrl.value !== null) {
      filter.departmentId = this.departmentCtrl.value;
    }
    if (this.countryCtrl.value !== null) {
      filter.countryCode = this.countryCtrl.value;
    }
    if (this.statusCtrl.value !== DEFAULT_STATUS) {
      filter.status = this.statusCtrl.value;
    }
    if (this.typeCtrl.value !== null) {
      filter.employmentType = this.typeCtrl.value;
    }
    if (this.levelCtrl.value !== null) {
      filter.jobLevel = this.levelCtrl.value;
    }
    return filter;
  }
}
