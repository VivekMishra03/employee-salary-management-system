import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { filter, map } from 'rxjs';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { MoneyPipe } from '../../../core/pipes/money.pipe';
import { AnalyticsService } from '../../../core/services/analytics.service';
import { debounceByTimer } from '../../../core/util/debounce';
import { BarChartComponent, BarDatum } from '../../../shared/charts/bar-chart.component';
import { panelState } from '../panel-state';

// FR-4.3: the API accepts 2 to 50 buckets and defaults to 12 (AnalyticsService.java).
const MIN_BUCKETS = 2;
const MAX_BUCKETS = 50;
const DEFAULT_BUCKETS = 12;

/** FR-4.3: salary histogram, drawn as one bar per bucket; the bucketing itself is the server's. */
@Component({
  selector: 'app-distribution-panel',
  imports: [ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatProgressBarModule, BarChartComponent],
  templateUrl: './distribution-panel.component.html',
  styleUrl: './distribution-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DistributionPanelComponent {
  private readonly analytics = inject(AnalyticsService);
  private readonly money = new MoneyPipe();

  readonly filter = input.required<AnalyticsFilter>();

  readonly bucketsCtrl = new FormControl<number | null>(DEFAULT_BUCKETS, {
    validators: [Validators.required, Validators.min(MIN_BUCKETS), Validators.max(MAX_BUCKETS), Validators.pattern(/^\d+$/)],
  });
  /** The count last accepted; an invalid entry never reaches it, so it never reaches the API. */
  private readonly buckets = signal(DEFAULT_BUCKETS);
  protected readonly bucketsInvalid = toSignal(
    this.bucketsCtrl.statusChanges.pipe(map(status => status === 'INVALID')), { initialValue: false });
  protected readonly range = { min: MIN_BUCKETS, max: MAX_BUCKETS };

  protected readonly state = panelState(
    computed(() => ({ filter: this.filter(), buckets: this.buckets() })),
    ({ filter, buckets }) => this.analytics.distribution(buckets, filter),
    'Could not load the salary distribution.',
  );

  protected readonly bars = computed((): BarDatum[] => {
    const s = this.state();
    if (s.status !== 'ready') {
      return [];
    }
    return s.data.map(b => {
      const label = `${this.money.transform(b.lower)} – ${this.money.transform(b.upper)}`;
      return { label, value: b.count, valueText: String(b.count), title: `${label}: ${b.count} employees` };
    });
  });

  constructor() {
    // Debounced like the directory search: typing 20 passes through 2, and each would cost a query.
    this.bucketsCtrl.valueChanges.pipe(
      debounceByTimer(400),
      filter(() => this.bucketsCtrl.valid),
      takeUntilDestroyed(),
    ).subscribe(value => this.buckets.set(value as number));
  }
}
