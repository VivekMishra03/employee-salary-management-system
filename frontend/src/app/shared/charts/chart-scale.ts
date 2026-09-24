// NFR-2: scale maths for the hand-built charts. Money is never computed here: values are only turned
// into pixel or percentage positions. Every function is total, so an empty, all-zero, flat or
// non-finite input yields finite numbers, never NaN or Infinity in a style attribute.

export interface BarExtent {
  /** Offset from the start of the axis, as a percentage of its length. */
  start: number;
  /** Bar length as a percentage of the axis length. */
  size: number;
}

export interface Axis {
  min: number;
  max: number;
  ticks: number[];
}

function finiteOrZero(value: number): number {
  return Number.isFinite(value) ? value : 0;
}

/**
 * Bars share a zero line. The domain is [min(0, smallest), max(0, largest)], so the largest
 * magnitude fills the axis, zero is zero-length, and negatives sit on the other side of the line.
 */
export function barExtents(values: readonly number[]): BarExtent[] {
  const cleaned = values.map(finiteOrZero);
  const lo = Math.min(0, ...cleaned);
  const hi = Math.max(0, ...cleaned);
  if (hi === lo) {
    return cleaned.map(() => ({ start: 0, size: 0 }));
  }
  const percent = (v: number): number => ((v - lo) / (hi - lo)) * 100;
  const zero = percent(0);
  return cleaned.map(v => ({ start: Math.min(percent(v), zero), size: Math.abs(percent(v) - zero) }));
}

/** Maps [min, max] onto [0, size]. A degenerate domain maps everything to the middle. */
export function linearScale(min: number, max: number, size: number): (value: number) => number {
  if (max === min) {
    return () => size / 2;
  }
  return value => ((value - min) / (max - min)) * size;
}

function niceNumber(x: number, round: boolean): number {
  const exponent = Math.floor(Math.log10(x));
  const fraction = x / 10 ** exponent;
  let nice: number;
  if (round) {
    nice = fraction < 1.5 ? 1 : fraction < 3 ? 2 : fraction < 7 ? 5 : 10;
  } else {
    nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
  }
  return nice * 10 ** exponent;
}

const DEFAULT_AXIS: Axis = { min: 0, max: 1, ticks: [0, 0.2, 0.4, 0.6, 0.8, 1] };

/**
 * A round-numbered axis that covers [lo, hi]. A flat series (lo === hi) is given room either side so
 * its line is drawn mid-chart instead of on an edge; a non-finite input falls back to 0..1.
 */
export function niceAxis(lo: number, hi: number, targetTicks = 5): Axis {
  if (!Number.isFinite(lo) || !Number.isFinite(hi)) {
    return { ...DEFAULT_AXIS, ticks: [...DEFAULT_AXIS.ticks] };
  }
  if (lo > hi) {
    [lo, hi] = [hi, lo];
  }
  if (lo === hi) {
    if (lo === 0) {
      return { ...DEFAULT_AXIS, ticks: [...DEFAULT_AXIS.ticks] };
    }
    const room = Math.abs(lo) / 2;
    lo -= room;
    hi += room;
  }
  const step = niceNumber(niceNumber(hi - lo, false) / (targetTicks - 1), true);
  const min = Math.floor(lo / step + 1e-9) * step;
  const max = Math.ceil(hi / step - 1e-9) * step;
  const count = Math.round((max - min) / step);
  const clean = (v: number): number => Number(v.toPrecision(12));
  const ticks = Array.from({ length: count + 1 }, (_, i) => clean(min + i * step));
  return { min: ticks[0], max: ticks[ticks.length - 1], ticks };
}
