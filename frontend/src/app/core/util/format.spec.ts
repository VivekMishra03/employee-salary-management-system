import { describeGenderGap, formatCompact, formatPercent, formatRatio, formatSignedPercent } from './format';

describe('formatRatio', () => {
  it('FR-4.4: shows a compa-ratio to two decimals', () => {
    expect(formatRatio(0.9)).toBe('0.90');
    expect(formatRatio(1.234)).toBe('1.23');
    expect(formatRatio(1.25)).toBe('1.25');
  });

  it('FR-4.4: shows a dash when there is no band and so no compa-ratio', () => {
    expect(formatRatio(null)).toBe('—');
  });
});

describe('formatPercent', () => {
  it('FR-4.6: shows a percentage to two decimals with a percent sign', () => {
    expect(formatPercent(3.5)).toBe('3.50%');
    expect(formatPercent(-0.25)).toBe('-0.25%');
  });

  it('FR-4.6: shows a dash for a null percentage (a period with no salary change)', () => {
    expect(formatPercent(null)).toBe('—');
  });
});

describe('formatCompact', () => {
  it('FR-4.6: abbreviates axis values so a payroll of hundreds of millions stays readable', () => {
    expect(formatCompact(1_250_000)).toBe('1.3M');
    expect(formatCompact(950_000_000)).toBe('950M');
    expect(formatCompact(0)).toBe('0');
  });
});

describe('describeGenderGap', () => {
  // GenderGapStat.java: positive = women paid less.
  it('FR-4.5: a positive gap says women are paid that much less', () => {
    expect(describeGenderGap(4.25)).toBe('Women paid 4.25% less');
  });

  it('FR-4.5: a negative gap says women are paid that much more, with no minus sign', () => {
    expect(describeGenderGap(-3.1)).toBe('Women paid 3.10% more');
  });

  it('FR-4.5: a zero gap says there is no gap', () => {
    expect(describeGenderGap(0)).toBe('No gap (0.00%)');
  });

  it('FR-4.5: an undefined gap (male mean or median of zero) is said to be undefined, not shown as 0', () => {
    expect(describeGenderGap(null)).toBe('Not defined for this group');
  });
});

describe('formatSignedPercent', () => {
  it('FR-4.5: shows the sign of a non-zero gap explicitly, two decimals', () => {
    expect(formatSignedPercent(4.25)).toBe('+4.25%');
    expect(formatSignedPercent(-3.1)).toBe('-3.10%');
  });

  it('FR-4.5: shows zero without a sign, and null as a dash', () => {
    expect(formatSignedPercent(0)).toBe('0.00%');
    expect(formatSignedPercent(null)).toBe('—');
  });
});
