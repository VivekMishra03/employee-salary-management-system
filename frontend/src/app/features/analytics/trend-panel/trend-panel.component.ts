import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { DateAdapter, MAT_DATE_FORMATS, MAT_NATIVE_DATE_FORMATS } from '@angular/material/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { NEVER, map, merge } from 'rxjs';
import { AnalyticsFilter, TrendInterval } from '../../../core/models/analytics.model';
import { MoneyPipe } from '../../../core/pipes/money.pipe';
import { AnalyticsService } from '../../../core/services/analytics.service';
import { toIsoDate } from '../../../core/util/date.util';
import { debounceByTimer } from '../../../core/util/debounce';
import { formatPercent } from '../../../core/util/format';
import { IsoDateAdapter } from '../../../core/util/iso-date-adapter';
import { TODAY } from '../../../core/util/today';
import { defaultTrendRange, validateTrendRange } from '../../../core/util/trend-range';
import { LineChartComponent, LinePoint } from '../../../shared/charts/line-chart.component';
import { panelState } from '../panel-state';

/** The same pause as the directory and dashboard search. Exported so the spec measures the pause, not a copy. */
export const DATE_DEBOUNCE_MS = 400;

/**
 * FR-4.6: total payroll and average pay increase per month, quarter or year. The range is checked
 * against the server's limits here so an inevitable refusal becomes a message and no request; the
 * server stays the authority and its own refusals are shown as they come.
 */
@Component({
  selector: 'app-trend-panel',
  imports: [
    ReactiveFormsModule, MatButtonToggleModule, MatCardModule, MatDatepickerModule, MatFormFieldModule,
    MatInputModule, MatProgressBarModule, MoneyPipe, LineChartComponent,
  ],
  providers: [
    { provide: DateAdapter, useClass: IsoDateAdapter },
    { provide: MAT_DATE_FORMATS, useValue: MAT_NATIVE_DATE_FORMATS },
  ],
  templateUrl: './trend-panel.component.html',
  styleUrl: './trend-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TrendPanelComponent {
  private readonly analytics = inject(AnalyticsService);
  private readonly money = new MoneyPipe();

  readonly filter = input.required<AnalyticsFilter>();

  readonly form = (() => {
    const range = defaultTrendRange(inject(TODAY)());
    return new FormGroup({
      from: new FormControl<Date | null>(range.from),
      to: new FormControl<Date | null>(range.to),
      interval: new FormControl<TrendInterval>('MONTH', { nonNullable: true }),
    });
  })();

  private readonly range = toSignal(
    this.form.valueChanges.pipe(map(() => this.form.getRawValue())),
    { initialValue: this.form.getRawValue() },
  );

  /**
   * The dates as they stood after a pause in typing. Partial input such as "2024" is already a valid date,
   * so without the pause most keystrokes would ask the server. Only the dates wait; the interval toggle and
   * the range check (`range`, above) react at once.
   */
  private readonly settledDates = toSignal(
    merge(this.form.controls.from.valueChanges, this.form.controls.to.valueChanges).pipe(
      map(() => {
        const { from, to } = this.form.getRawValue();
        return { from, to };
      }),
      debounceByTimer(DATE_DEBOUNCE_MS),
    ),
    { initialValue: { from: this.form.controls.from.value, to: this.form.controls.to.value } },
  );

  protected readonly rangeError = computed(() => {
    const { from, to, interval } = this.range();
    return validateTrendRange(from, to, interval);
  });

  /**
   * null while the range is refused, or while a date is still settling: nothing is asked of the server, and
   * a request in flight is abandoned, so the answer to an older range can never be shown for the new one.
   */
  private readonly request = computed(() => {
    const { from, to, interval } = this.range();
    const settled = this.settledDates();
    const settling = from?.getTime() !== settled.from?.getTime() || to?.getTime() !== settled.to?.getTime();
    if (this.rangeError() !== null || settling || !from || !to) {
      return null;
    }
    return { filter: this.filter(), from: toIsoDate(from), to: toIsoDate(to), interval };
  });

  // While the range is refused the template shows the message instead of this state, so NEVER (which
  // leaves it 'loading') is never displayed.
  protected readonly state = panelState(
    this.request,
    request => request === null
      ? NEVER
      : this.analytics.trend(request.filter, request.from, request.to, request.interval),
    'Could not load the compensation trend.',
  );

  protected readonly rows = computed(() => {
    const s = this.state();
    if (s.status !== 'ready') {
      return [];
    }
    return s.data.map(p => ({
      period: p.period,
      payroll: p.totalPayrollUsd,
      increaseText: formatPercent(p.avgIncreasePct),
    }));
  });

  protected readonly payrollPoints = computed((): LinePoint[] => {
    const s = this.state();
    return s.status !== 'ready' ? [] : s.data.map(p => ({
      label: p.period, value: p.totalPayrollUsd, valueText: this.money.transform(p.totalPayrollUsd),
    }));
  });

  /** A period with no qualifying salary change is a null: a gap in the line, not a 0% increase. */
  protected readonly increasePoints = computed((): LinePoint[] => {
    const s = this.state();
    return s.status !== 'ready' ? [] : s.data.map(p => ({
      label: p.period, value: p.avgIncreasePct, valueText: formatPercent(p.avgIncreasePct),
    }));
  });

  protected readonly formatPercent = formatPercent;
}
