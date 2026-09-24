// NFR-2: display formatting for analytics figures. The API rounds (HALF_UP, 2 places); these only render.
const DASH = '—';
const TWO_PLACES = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const COMPACT = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });

/** FR-4.4: compa-ratio to two decimals; a dash when there is no band. */
export function formatRatio(value: number | null | undefined): string {
  return value === null || value === undefined ? DASH : TWO_PLACES.format(value);
}

/** FR-4.6: a percentage to two decimals; a dash for null (no qualifying salary change). */
export function formatPercent(value: number | null | undefined): string {
  return value === null || value === undefined ? DASH : `${TWO_PLACES.format(value)}%`;
}

/** FR-4.5: a signed percentage; the sign is omitted when the value shows as zero, so "+0.00%" never appears. */
export function formatSignedPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return DASH;
  }
  const magnitude = TWO_PLACES.format(Math.abs(value));
  if (magnitude === TWO_PLACES.format(0)) {
    return `${magnitude}%`;
  }
  return `${value > 0 ? '+' : '-'}${magnitude}%`;
}

/** Axis labels: 1,250,000 becomes 1.3M so a payroll-sized axis stays readable. */
export function formatCompact(value: number): string {
  return COMPACT.format(value);
}

/**
 * FR-4.5: the sign convention of GenderGapStat.java is positive = women paid less, so the words say
 * so explicitly instead of leaving the reader to decode a sign.
 */
export function describeGenderGap(gapPct: number | null): string {
  if (gapPct === null) {
    return 'Not defined for this group';
  }
  if (gapPct === 0) {
    return `No gap (${TWO_PLACES.format(0)}%)`;
  }
  return `Women paid ${TWO_PLACES.format(Math.abs(gapPct))}% ${gapPct > 0 ? 'less' : 'more'}`;
}
