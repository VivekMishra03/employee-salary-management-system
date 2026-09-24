import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { AnalyticsFilter } from '../../core/models/analytics.model';
import { AnalyticsFiltersComponent } from './analytics-filters/analytics-filters.component';
import { ComparisonPanelComponent } from './comparison-panel/comparison-panel.component';
import { DistributionPanelComponent } from './distribution-panel/distribution-panel.component';
import { GenderGapPanelComponent } from './gender-gap-panel/gender-gap-panel.component';
import { PayBandsPanelComponent } from './pay-bands-panel/pay-bands-panel.component';
import { SummaryPanelComponent } from './summary-panel/summary-panel.component';
import { TrendPanelComponent } from './trend-panel/trend-panel.component';

/**
 * FR-4.1 - FR-4.7: the analytics dashboard. This component only owns the filter; every panel takes it
 * as an input and fetches, and shows loading and failure, on its own (panelState), so one slow or
 * failing endpoint never holds up the rest.
 */
@Component({
  selector: 'app-analytics',
  imports: [
    AnalyticsFiltersComponent,
    SummaryPanelComponent,
    ComparisonPanelComponent,
    DistributionPanelComponent,
    PayBandsPanelComponent,
    GenderGapPanelComponent,
    TrendPanelComponent,
  ],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsComponent {
  /** The default slice: no constraints, which the API reads as active and on-leave employees. */
  protected readonly filter = signal<AnalyticsFilter>({});
}
