import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AnalyticsFilter, GroupBy } from '../../../core/models/analytics.model';
import { MoneyPipe } from '../../../core/pipes/money.pipe';
import { AnalyticsService } from '../../../core/services/analytics.service';
import { BarChartComponent, BarDatum } from '../../../shared/charts/bar-chart.component';
import { panelState } from '../panel-state';

/** FR-4.2: median pay per department, country or job level, with headcount. The server does the grouping. */
@Component({
  selector: 'app-comparison-panel',
  imports: [MatButtonToggleModule, MatCardModule, MatProgressBarModule, MoneyPipe, BarChartComponent],
  templateUrl: './comparison-panel.component.html',
  styleUrl: './comparison-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparisonPanelComponent {
  private readonly analytics = inject(AnalyticsService);
  private readonly money = new MoneyPipe();

  readonly filter = input.required<AnalyticsFilter>();
  protected readonly groupBy = signal<GroupBy>('DEPARTMENT');

  protected readonly state = panelState(
    computed(() => ({ filter: this.filter(), groupBy: this.groupBy() })),
    ({ filter, groupBy }) => this.analytics.byGroup(groupBy, filter),
    'Could not load the pay comparison.',
  );

  // Not [(value)]: the group's setter emits valueChange(undefined) at start-up, before its toggles exist.
  protected pick(change: MatButtonToggleChange): void {
    this.groupBy.set(change.value);
  }

  /** A group with no median is drawn as an empty bar labelled with a dash, not as a real zero. */
  protected readonly bars = computed((): BarDatum[] => {
    const s = this.state();
    if (s.status !== 'ready') {
      return [];
    }
    return s.data.map(g => {
      const median = this.money.transform(g.median);
      return {
        label: g.label,
        value: g.median ?? 0,
        valueText: median,
        title: `${g.label}: median ${median}, ${g.headcount} employees`,
      };
    });
  });
}
