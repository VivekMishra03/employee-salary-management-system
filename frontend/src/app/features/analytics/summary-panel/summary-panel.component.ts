import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AnalyticsFilter } from '../../../core/models/analytics.model';
import { MoneyPipe } from '../../../core/pipes/money.pipe';
import { AnalyticsService } from '../../../core/services/analytics.service';
import { formatIsoDate } from '../../../core/util/date.util';
import { panelState } from '../panel-state';

type MoneyFigure = 'totalPayroll' | 'mean' | 'median' | 'p25' | 'p75';

const FIGURES: readonly { key: MoneyFigure; label: string }[] = [
  { key: 'totalPayroll', label: 'Total annual payroll' },
  { key: 'mean', label: 'Mean' },
  { key: 'median', label: 'Median' },
  { key: 'p25', label: '25th percentile' },
  { key: 'p75', label: '75th percentile' },
];

/** FR-4.1: headcount, payroll and pay statistics for the filtered slice; all figures are computed by the server. */
@Component({
  selector: 'app-summary-panel',
  imports: [MatCardModule, MatIconModule, MatProgressBarModule, MoneyPipe],
  templateUrl: './summary-panel.component.html',
  styleUrl: './summary-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SummaryPanelComponent {
  private readonly analytics = inject(AnalyticsService);

  readonly filter = input.required<AnalyticsFilter>();

  protected readonly state = panelState(
    this.filter,
    filter => this.analytics.summary(filter),
    'Could not load the summary.',
  );

  /** An empty slice reports zero payroll; a dash says "no figure" where a zero would read as a real one. */
  protected readonly view = computed(() => {
    const s = this.state();
    if (s.status !== 'ready') {
      return null;
    }
    const data = s.data;
    const empty = data.headcount === 0;
    return {
      empty,
      // The rate table's date is not about the slice, so an empty slice still shows it.
      ratesNote: data.ratesAsOf
        ? `Exchange rates as of ${formatIsoDate(data.ratesAsOf)}.`
        : 'Exchange-rate date unavailable.',
      headcount: data.headcount,
      figures: FIGURES.map(f => ({ ...f, value: empty ? null : data[f.key] })),
    };
  });
}
