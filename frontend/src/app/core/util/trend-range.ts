import { TrendInterval } from '../models/analytics.model';
import { toIsoDate } from './date.util';

// Mirrors TrendService.java (MAX_PERIODS, MIN_YEAR, MAX_YEAR). The server stays the authority; this
// only turns an inevitable rejection into a message before the request is sent.
export const MAX_TREND_PERIODS = 120;
export const MIN_TREND_YEAR = 1900;
export const MAX_TREND_YEAR = 2200;

const MONTHS_PER_BUCKET: Record<TrendInterval, number> = { MONTH: 1, QUARTER: 3, YEAR: 12 };

/** FR-4.6: five years ending on `today`, both ends at midnight (a calendar day has no time). 29 February clamps to 28 February rather than rolling into March. */
export function defaultTrendRange(today: Date): { from: Date; to: Date } {
  const to = new Date(2000, 0, 1);
  to.setFullYear(today.getFullYear(), today.getMonth(), today.getDate());
  const from = new Date(2000, 0, 1);
  from.setFullYear(today.getFullYear() - 5, today.getMonth(), today.getDate());
  if (from.getMonth() !== today.getMonth()) {
    from.setDate(0); // the last day of the previous month
  }
  return { from, to };
}

/** Calendar buckets touched by [from, to], both ends included (TrendInterval.periodCount on the server). */
export function trendPeriodCount(from: Date, to: Date, interval: TrendInterval): number {
  const months = MONTHS_PER_BUCKET[interval];
  const bucketStart = (date: Date): number =>
    date.getFullYear() * 12 + Math.floor(date.getMonth() / months) * months;
  return (bucketStart(to) - bucketStart(from)) / months + 1;
}

/** FR-4.6: a friendly message for a range the server would reject, or null when it would accept it. */
export function validateTrendRange(from: Date | null, to: Date | null, interval: TrendInterval): string | null {
  if (!from || !to) {
    return 'Choose both a start and an end date.';
  }
  const outside = [from, to].some(d => d.getFullYear() < MIN_TREND_YEAR || d.getFullYear() > MAX_TREND_YEAR);
  if (outside) {
    return `Dates must fall between the years ${MIN_TREND_YEAR} and ${MAX_TREND_YEAR}.`;
  }
  // Compared as yyyy-MM-dd text: calendar days, not instants, and safe because years are already 4 digits here.
  if (toIsoDate(from) > toIsoDate(to)) {
    return 'The end date cannot be before the start date.';
  }
  if (trendPeriodCount(from, to, interval) > MAX_TREND_PERIODS) {
    return `That range covers more than ${MAX_TREND_PERIODS} periods; use at most ${MAX_TREND_PERIODS}, or choose a coarser interval.`;
  }
  return null;
}
