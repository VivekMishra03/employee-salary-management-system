import { TestBed } from '@angular/core/testing';
import { TODAY } from './today';
import { defaultTrendRange, trendPeriodCount, validateTrendRange } from './trend-range';

const d = (y: number, m: number, day: number): Date => {
  const date = new Date(2000, 0, 1);
  date.setFullYear(y, m - 1, day);
  return date;
};

describe('TODAY token', () => {
  it('NFR-3: is injectable and can be replaced by a fixed date, so no spec depends on the wall clock', () => {
    TestBed.configureTestingModule({ providers: [{ provide: TODAY, useValue: () => d(2026, 9, 24) }] });
    expect(TestBed.inject(TODAY)()).toEqual(d(2026, 9, 24));
  });

  it('FR-4.6: by default it yields a Date (the one place the real clock is read)', () => {
    expect(TestBed.inject(TODAY)() instanceof Date).toBeTrue();
  });
});

describe('defaultTrendRange', () => {
  it('FR-4.6: is five years ending on the given day', () => {
    const range = defaultTrendRange(d(2026, 9, 24));
    expect(range.from).toEqual(d(2021, 9, 24));
    expect(range.to).toEqual(d(2026, 9, 24));
  });

  it('FR-4.6: does not roll 29 February into March when five years earlier is not a leap year', () => {
    // 2024-02-29 minus five years is 2019-02-29, which does not exist.
    const range = defaultTrendRange(d(2024, 2, 29));
    expect(range.from).toEqual(d(2019, 2, 28));
    expect(range.to).toEqual(d(2024, 2, 29));
  });

  it('FR-4.6: both ends are calendar days (midnight), whatever time of day the clock read', () => {
    const range = defaultTrendRange(new Date(2026, 8, 24, 15, 30, 45));
    expect(range.from).toEqual(d(2021, 9, 24));
    expect(range.to).toEqual(d(2026, 9, 24));
  });

  it('FR-4.6: does not mutate the date it was given', () => {
    const today = d(2026, 9, 24);
    defaultTrendRange(today);
    expect(today).toEqual(d(2026, 9, 24));
  });
});

describe('trendPeriodCount', () => {
  // Mirrors TrendInterval.periodCount on the server: calendar buckets touched, both ends included.
  it('FR-4.6: counts calendar months touched, not days', () => {
    expect(trendPeriodCount(d(2026, 1, 31), d(2026, 2, 1), 'MONTH')).toBe(2);
    expect(trendPeriodCount(d(2026, 3, 5), d(2026, 3, 20), 'MONTH')).toBe(1);
    expect(trendPeriodCount(d(2021, 9, 24), d(2026, 9, 24), 'MONTH')).toBe(61);
  });

  it('FR-4.6: counts quarters touched (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec)', () => {
    expect(trendPeriodCount(d(2026, 3, 31), d(2026, 4, 1), 'QUARTER')).toBe(2);
    expect(trendPeriodCount(d(2026, 1, 1), d(2026, 3, 31), 'QUARTER')).toBe(1);
    expect(trendPeriodCount(d(2021, 9, 24), d(2026, 9, 24), 'QUARTER')).toBe(21);
  });

  it('FR-4.6: counts years touched', () => {
    expect(trendPeriodCount(d(2025, 12, 31), d(2026, 1, 1), 'YEAR')).toBe(2);
    expect(trendPeriodCount(d(2021, 9, 24), d(2026, 9, 24), 'YEAR')).toBe(6);
  });
});

describe('validateTrendRange', () => {
  it('FR-4.6: accepts the default five-year monthly range', () => {
    expect(validateTrendRange(d(2021, 9, 24), d(2026, 9, 24), 'MONTH')).toBeNull();
  });

  it('FR-4.6: accepts a range of exactly 120 periods and refuses 121', () => {
    // 2016-01 .. 2025-12 is 120 months; one more month is 121.
    expect(validateTrendRange(d(2016, 1, 1), d(2025, 12, 31), 'MONTH')).toBeNull();
    expect(validateTrendRange(d(2016, 1, 1), d(2026, 1, 1), 'MONTH')).toMatch(/at most 120/);
  });

  it('FR-4.6: refuses a range whose start is after its end, with a message that says so', () => {
    expect(validateTrendRange(d(2026, 9, 24), d(2026, 9, 23), 'MONTH')).toMatch(/before/i);
  });

  it('FR-4.6: accepts a single day (from equal to to)', () => {
    expect(validateTrendRange(d(2026, 9, 24), d(2026, 9, 24), 'YEAR')).toBeNull();
  });

  it('FR-4.6: compares calendar days, so a start later in the day than the end of the same day is still one day', () => {
    expect(validateTrendRange(new Date(2026, 8, 24, 15, 30), d(2026, 9, 24), 'MONTH')).toBeNull();
  });

  it('FR-4.6: refuses years outside 1900 to 2200 on either end', () => {
    expect(validateTrendRange(d(1899, 12, 31), d(1900, 1, 1), 'YEAR')).toMatch(/1900/);
    expect(validateTrendRange(d(2200, 12, 31), d(2201, 1, 1), 'YEAR')).toMatch(/2200/);
    expect(validateTrendRange(d(1900, 1, 1), d(1900, 1, 2), 'MONTH')).toBeNull();
    expect(validateTrendRange(d(2200, 12, 30), d(2200, 12, 31), 'MONTH')).toBeNull();
  });

  it('FR-4.6: asks for both dates when one is missing', () => {
    expect(validateTrendRange(null, d(2026, 9, 24), 'MONTH')).toMatch(/both/i);
    expect(validateTrendRange(d(2026, 9, 24), null, 'MONTH')).toMatch(/both/i);
  });

  it('FR-4.6: quarterly and yearly ranges may reach further back than monthly ones', () => {
    expect(validateTrendRange(d(1996, 1, 1), d(2025, 12, 31), 'QUARTER')).toBeNull();
    expect(validateTrendRange(d(1996, 1, 1), d(2026, 1, 1), 'QUARTER')).toMatch(/at most 120/);
    expect(validateTrendRange(d(1907, 1, 1), d(2026, 1, 1), 'YEAR')).toBeNull();
  });
});
