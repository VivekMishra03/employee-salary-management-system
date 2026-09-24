import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AnalyticsFilter, PayBandAdherence, PayBandReport } from '../../../core/models/analytics.model';
import { MoneyPipe } from '../../../core/pipes/money.pipe';
import { AnalyticsService } from '../../../core/services/analytics.service';
import { formatRatio } from '../../../core/util/format';
import { PanelState, panelState } from '../panel-state';

const ADHERENCE_LABEL: Record<PayBandAdherence, string> = {
  BELOW: 'Below band',
  WITHIN: 'Within band',
  ABOVE: 'Above band',
  NO_BAND: 'No band',
};

/**
 * FR-4.4: how many people are below, within and above their pay band, and who they are. The counts cover
 * the whole filtered slice; the table is one server page of it (the API caps a page at 100), narrowed by
 * the adherence the reader picked from the counts.
 */
@Component({
  selector: 'app-pay-bands-panel',
  imports: [RouterLink, MatCardModule, MatChipsModule, MatPaginatorModule, MatProgressBarModule, MoneyPipe],
  templateUrl: './pay-bands-panel.component.html',
  styleUrl: './pay-bands-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PayBandsPanelComponent {
  private readonly analytics = inject(AnalyticsService);

  readonly filter = input.required<AnalyticsFilter>();
  readonly pageSizeOptions = [10, 20, 50, 100];

  protected readonly adherence = signal<PayBandAdherence | null>(null);
  protected readonly pageSize = signal(20);
  /** A new filter or adherence changes what page N means, so it returns to the first page in the same pass. */
  protected readonly pageIndex = linkedSignal({
    source: () => ({ filter: this.filter(), adherence: this.adherence() }),
    computation: () => 0,
  });

  protected readonly state = panelState(
    computed(() => ({
      filter: this.filter(), adherence: this.adherence(), page: this.pageIndex(), size: this.pageSize(),
    })),
    ({ filter, adherence, page, size }) =>
      this.analytics.payBands(filter, { page, size, adherence: adherence ?? undefined }),
    'Could not load the pay-band report.',
  );

  /** The last report received: kept while the next page loads so the table does not blink away, dropped on error. */
  protected readonly report = linkedSignal<PanelState<PayBandReport>, PayBandReport | null>({
    source: this.state,
    computation: (s, previous) => {
      if (s.status === 'ready') {
        return s.data;
      }
      return s.status === 'error' ? null : (previous?.value ?? null);
    },
  });

  protected readonly counts = computed(() => {
    const report = this.report();
    if (!report) {
      return [];
    }
    const { below, within, above, noBand } = report.counts;
    return ([
      ['BELOW', below], ['WITHIN', within], ['ABOVE', above], ['NO_BAND', noBand],
    ] as const).map(([adherence, count]) => ({ adherence, label: ADHERENCE_LABEL[adherence], count }));
  });

  protected readonly rows = computed(() =>
    (this.report()?.employees.content ?? []).map(e => ({
      ...e,
      adherenceLabel: ADHERENCE_LABEL[e.adherence],
      compaRatioText: formatRatio(e.compaRatio),
    })));

  protected toggleAdherence(value: PayBandAdherence): void {
    this.adherence.update(current => (current === value ? null : value));
  }

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }
}
