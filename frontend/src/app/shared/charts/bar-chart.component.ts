import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { barExtents } from './chart-scale';

/** One bar. `title` is the exact figure shown on hover; `valueText` is the figure shown beside the bar. */
export interface BarDatum {
  label: string;
  value: number;
  title: string;
  valueText?: string;
}

/**
 * FR-4.2 / FR-4.3: a dependency-free bar chart (ADR-0012 keeps the bundle small and the tests
 * deterministic). Horizontal bars. The bars are decorative to assistive technology
 * (aria-hidden); a visually hidden table carries the same figures, so nothing is chart-only.
 */
@Component({
  selector: 'app-bar-chart',
  templateUrl: './bar-chart.component.html',
  styleUrl: './bar-chart.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BarChartComponent {
  readonly bars = input.required<BarDatum[]>();
  /** The accessible name of the chart, used as the caption of the text alternative. */
  readonly caption = input.required<string>();

  protected readonly rows = computed(() => {
    const data = this.bars();
    const extents = barExtents(data.map(d => d.value));
    return data.map((d, i) => ({
      label: d.label,
      title: d.title,
      valueText: d.valueText ?? (Number.isFinite(d.value) ? String(d.value) : '—'),
      start: extents[i].start,
      size: extents[i].size,
    }));
  });
}
