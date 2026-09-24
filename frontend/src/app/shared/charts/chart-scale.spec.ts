import { barExtents, linearScale, niceAxis } from './chart-scale';

// NFR-2: a chart must not misrepresent money. The scale maths is pure so it can be checked directly,
// including the inputs that break naive scaling (zero, negatives, one value, huge values).
describe('barExtents', () => {
  it('NFR-2: sizes bars in proportion to the largest value, which fills the axis', () => {
    expect(barExtents([50, 100, 25])).toEqual([
      { start: 0, size: 50 }, { start: 0, size: 100 }, { start: 0, size: 25 },
    ]);
  });

  it('NFR-2: an empty list has no bars', () => {
    expect(barExtents([])).toEqual([]);
  });

  it('NFR-2: all-zero data gives zero-length bars, never NaN from a 0/0 division', () => {
    expect(barExtents([0, 0, 0])).toEqual([
      { start: 0, size: 0 }, { start: 0, size: 0 }, { start: 0, size: 0 },
    ]);
  });

  it('NFR-2: a single value fills the axis', () => {
    expect(barExtents([7])).toEqual([{ start: 0, size: 100 }]);
  });

  it('NFR-2: a zero among positive values stays zero-length rather than being drawn as a sliver', () => {
    expect(barExtents([0, 10])[0]).toEqual({ start: 0, size: 0 });
  });

  it('NFR-2: negative values hang below a shared zero line and never overlap the positive ones', () => {
    const [negative, positive] = barExtents([-50, 100]);
    expect(negative.size).toBeCloseTo(100 / 3, 6);
    expect(positive.size).toBeCloseTo(200 / 3, 6);
    expect(negative.start).toBe(0);
    expect(positive.start).toBeCloseTo(negative.size, 6); // the bars meet at the zero line
  });

  it('NFR-2: all-negative data is drawn back from the zero line at the far end, largest magnitude longest', () => {
    const [small, large] = barExtents([-10, -40]);
    expect(large).toEqual({ start: 0, size: 100 });
    expect(small).toEqual({ start: 75, size: 25 });
  });

  it('NFR-2: very large money values keep their ratios exactly', () => {
    expect(barExtents([1_000_000_000_000_000, 500_000_000_000_000])).toEqual([
      { start: 0, size: 100 }, { start: 0, size: 50 },
    ]);
  });

  it('NFR-2: a non-finite value is drawn as zero instead of poisoning the other bars', () => {
    const extents = barExtents([Number.NaN, 10, Number.POSITIVE_INFINITY]);
    for (const e of extents) {
      expect(Number.isFinite(e.start)).toBeTrue();
      expect(Number.isFinite(e.size)).toBeTrue();
    }
    expect(extents[0].size).toBe(0);
    expect(extents[1].size).toBe(100);
  });
});

describe('linearScale', () => {
  it('NFR-2: maps the domain ends to the range ends and the middle to the middle', () => {
    const scale = linearScale(100, 300, 400);
    expect(scale(100)).toBe(0);
    expect(scale(300)).toBe(400);
    expect(scale(200)).toBe(200);
  });

  it('NFR-2: a degenerate domain maps everything to the centre of the range instead of dividing by zero', () => {
    const scale = linearScale(5, 5, 200);
    expect(scale(5)).toBe(100);
  });
});

describe('niceAxis', () => {
  it('NFR-2: a zero-based range gets round ticks that cover it', () => {
    expect(niceAxis(0, 100)).toEqual({ min: 0, max: 100, ticks: [0, 20, 40, 60, 80, 100] });
  });

  it('NFR-2: the axis always covers the data', () => {
    const axis = niceAxis(3.2, 97.4);
    expect(axis.min).toBeLessThanOrEqual(3.2);
    expect(axis.max).toBeGreaterThanOrEqual(97.4);
    expect(axis.ticks[0]).toBe(axis.min);
    expect(axis.ticks[axis.ticks.length - 1]).toBe(axis.max);
  });

  it('NFR-2: ticks are free of floating-point noise (0.1 steps are 0.2, not 0.30000000000000004)', () => {
    expect(niceAxis(0, 1).ticks).toEqual([0, 0.2, 0.4, 0.6, 0.8, 1]);
  });

  it('NFR-2: a flat series still gets an axis that contains its value, with at least two ticks', () => {
    const axis = niceAxis(5000, 5000);
    expect(axis.min).toBeLessThan(5000);
    expect(axis.max).toBeGreaterThan(5000);
    expect(axis.ticks.length).toBeGreaterThanOrEqual(2);
  });

  it('NFR-2: a flat zero series gets the axis 0 to 1', () => {
    expect(niceAxis(0, 0)).toEqual({ min: 0, max: 1, ticks: [0, 0.2, 0.4, 0.6, 0.8, 1] });
  });

  it('NFR-2: negative ranges are covered', () => {
    const axis = niceAxis(-100, -20);
    expect(axis.min).toBeLessThanOrEqual(-100);
    expect(axis.max).toBeGreaterThanOrEqual(-20);
  });

  it('NFR-2: payroll-sized values (trillions) give finite, strictly increasing ticks', () => {
    const axis = niceAxis(1_234_567_890_123, 3_456_789_012_345);
    expect(axis.ticks.every(Number.isFinite)).toBeTrue();
    for (let i = 1; i < axis.ticks.length; i++) {
      expect(axis.ticks[i]).toBeGreaterThan(axis.ticks[i - 1]);
    }
    expect(axis.min).toBeLessThanOrEqual(1_234_567_890_123);
    expect(axis.max).toBeGreaterThanOrEqual(3_456_789_012_345);
  });

  it('NFR-2: non-finite input falls back to 0 to 1 rather than producing NaN ticks', () => {
    expect(niceAxis(Number.NaN, 10).min).toBe(0);
    expect(niceAxis(Number.NaN, 10).max).toBe(1);
  });
});
