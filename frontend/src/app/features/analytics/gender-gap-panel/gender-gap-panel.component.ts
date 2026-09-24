import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AnalyticsFilter, GenderGapGroupBy } from '../../../core/models/analytics.model';
import { AnalyticsService } from '../../../core/services/analytics.service';
import { describeGenderGap, formatSignedPercent } from '../../../core/util/format';
import { panelState } from '../panel-state';

interface Gap {
  value: string;
  words: string;
}

type GapRow =
  | { suppressed: true; key: string; label: string }
  | { suppressed: false; key: string; label: string; men: string; women: string; mean: Gap; median: Gap };

const NOT_AVAILABLE = '—';

const gap = (pct: number | null): Gap => ({ value: formatSignedPercent(pct), words: describeGenderGap(pct) });

/**
 * FR-4.5: mean and median gender pay gap by department or job level. Small groups arrive suppressed
 * from the server; the row model is built so a suppressed group carries only its name, so nothing in
 * the template can show a count or a gap for it whatever the response contained.
 */
@Component({
  selector: 'app-gender-gap-panel',
  imports: [MatButtonToggleModule, MatCardModule, MatIconModule, MatProgressBarModule],
  templateUrl: './gender-gap-panel.component.html',
  styleUrl: './gender-gap-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GenderGapPanelComponent {
  private readonly analytics = inject(AnalyticsService);

  readonly filter = input.required<AnalyticsFilter>();
  protected readonly groupBy = signal<GenderGapGroupBy>('DEPARTMENT');

  protected readonly state = panelState(
    computed(() => ({ filter: this.filter(), groupBy: this.groupBy() })),
    ({ filter, groupBy }) => this.analytics.genderGap(groupBy, filter),
    'Could not load the gender pay gap.',
  );

  protected readonly rows = computed((): GapRow[] => {
    const s = this.state();
    if (s.status !== 'ready') {
      return [];
    }
    return s.data.map((g): GapRow => g.suppressed
      ? { suppressed: true, key: g.key, label: g.label }
      : {
          suppressed: false,
          key: g.key,
          label: g.label,
          men: g.maleCount === null ? NOT_AVAILABLE : String(g.maleCount),
          women: g.femaleCount === null ? NOT_AVAILABLE : String(g.femaleCount),
          mean: gap(g.meanGapPct),
          median: gap(g.medianGapPct),
        });
  });

  // Not [(value)]: the group's setter emits valueChange(undefined) at start-up, before its toggles exist.
  protected pick(change: MatButtonToggleChange): void {
    this.groupBy.set(change.value);
  }
}
