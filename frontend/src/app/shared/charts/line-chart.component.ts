import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { formatCompact } from '../../core/util/format';
import { linearScale, niceAxis } from './chart-scale';

/** One period. A null value is a gap in the line, never a zero. */
export interface LinePoint {
  label: string;
  value: number | null;
  /** The figure as it should be read (hover and text alternative); defaults to the raw number. */
  valueText?: string;
}

const WIDTH = 640;
const MARGIN = { top: 12, right: 16, bottom: 28, left: 64 };

/**
 * FR-4.6: a dependency-free line chart. The SVG is decorative to assistive technology
 * (aria-hidden); a visually hidden table carries every period and value.
 */
@Component({
  selector: 'app-line-chart',
  templateUrl: './line-chart.component.html',
  styleUrl: './line-chart.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LineChartComponent {
  readonly points = input.required<LinePoint[]>();
  /** The accessible name of the chart, used as the caption of the text alternative. */
  readonly caption = input.required<string>();
  readonly height = input(240);
  /** How a y-axis tick is written; compact by default (1.3M). */
  readonly axisFormat = input<(value: number) => string>(formatCompact);

  protected readonly width = WIDTH;

  protected readonly hasData = computed(() => this.points().some(p => p.value !== null && Number.isFinite(p.value)));

  protected readonly rows = computed(() =>
    this.points().map(p => ({
      label: p.label,
      valueText: p.value === null || !Number.isFinite(p.value) ? '—' : (p.valueText ?? String(p.value)),
    })));

  protected readonly model = computed(() => {
    const points = this.points();
    const plotW = WIDTH - MARGIN.left - MARGIN.right;
    const plotH = this.height() - MARGIN.top - MARGIN.bottom;
    const finite = points.map(p => p.value).filter((v): v is number => v !== null && Number.isFinite(v));
    const axis = niceAxis(Math.min(...finite), Math.max(...finite));
    const yScale = linearScale(axis.min, axis.max, plotH);
    const xScale = linearScale(0, points.length - 1, plotW);
    const fmt = (n: number): string => n.toFixed(2);

    const dots: { cx: string; cy: string; title: string }[] = [];
    const segments: string[] = [];
    let current: string[] = [];
    points.forEach((p, i) => {
      if (p.value === null || !Number.isFinite(p.value)) {
        if (current.length) {
          segments.push(current.join(' '));
        }
        current = [];
        return;
      }
      const x = MARGIN.left + xScale(i);
      const y = MARGIN.top + plotH - yScale(p.value);
      current.push(`${current.length ? 'L' : 'M'}${fmt(x)},${fmt(y)}`);
      dots.push({ cx: fmt(x), cy: fmt(y), title: `${p.label}: ${p.valueText ?? p.value}` });
    });
    if (current.length) {
      segments.push(current.join(' '));
    }

    const ticks = axis.ticks.map(t => ({
      y: fmt(MARGIN.top + plotH - yScale(t)),
      text: this.axisFormat()(t),
    }));
    const last = points.length - 1;
    const xLabels = points.length === 0 ? [] : [
      { x: fmt(MARGIN.left + xScale(0)), text: points[0].label, anchor: points.length === 1 ? 'middle' : 'start' },
      ...(last > 0 ? [{ x: fmt(MARGIN.left + xScale(last)), text: points[last].label, anchor: 'end' }] : []),
    ];
    return {
      segments, dots, ticks, xLabels,
      plotLeft: MARGIN.left, plotRight: WIDTH - MARGIN.right, baseline: this.height() - 8,
    };
  });
}
