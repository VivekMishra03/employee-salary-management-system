import { formatIsoDate, toIsoDate } from './date.util';

// FR-3.6: back-dated and future-dated salary changes are keyed by the calendar date the HR Manager
// picked. A Date from the datepicker is local midnight; serialising it through toISOString() would
// shift it to the previous day anywhere east of UTC.
describe('toIsoDate', () => {
  it('FR-3.6: formats a local-midnight Date as its own calendar day, not the UTC day', () => {
    expect(toIsoDate(new Date(2026, 0, 15, 0, 0, 0))).toBe('2026-01-15');
  });

  it('FR-3.6: zero-pads month and day', () => {
    expect(toIsoDate(new Date(2026, 8, 3, 23, 59, 59))).toBe('2026-09-03');
  });

  it('FR-3.6: 29 February on a leap year survives the round trip', () => {
    expect(toIsoDate(new Date(2028, 1, 29, 12, 0, 0))).toBe('2028-02-29');
  });
});

// Assumption 2 (requirements.md): the exchange-rate date is shown. The API sends a calendar date
// (yyyy-MM-dd); building a Date from it with new Date('yyyy-MM-dd') would be UTC midnight, the
// previous day in every zone west of UTC, so the text is built from the parts and no Date is made.
describe('formatIsoDate', () => {
  it('Assumption 2: writes the calendar day with the month spelled out, so it reads the same in every locale', () => {
    expect(formatIsoDate('2026-09-24')).toBe('24 September 2026');
  });

  it('Assumption 2: a single-digit day and month lose their zero padding', () => {
    expect(formatIsoDate('2026-03-05')).toBe('5 March 2026');
  });

  it('Assumption 2: 29 February on a leap year is kept, and 1 January is not shifted to the year before', () => {
    expect(formatIsoDate('2028-02-29')).toBe('29 February 2028');
    expect(formatIsoDate('2026-01-01')).toBe('1 January 2026');
  });

  it('Assumption 2: something that is not a yyyy-MM-dd date is returned untouched rather than turned into "NaN"', () => {
    expect(formatIsoDate('yesterday')).toBe('yesterday');
    expect(formatIsoDate('2026-13-01')).toBe('2026-13-01');
  });

  it('Assumption 2: a day the month does not have is returned untouched rather than shown as a date', () => {
    expect(formatIsoDate('2026-02-30')).toBe('2026-02-30');
    expect(formatIsoDate('2026-04-31')).toBe('2026-04-31');
    expect(formatIsoDate('2026-01-00')).toBe('2026-01-00');
    expect(formatIsoDate('2026-01-32')).toBe('2026-01-32');
  });

  it('Assumption 2: 29 February is a date only in a leap year, including the century rule', () => {
    expect(formatIsoDate('2028-02-29')).toBe('29 February 2028');
    expect(formatIsoDate('2000-02-29')).toBe('29 February 2000');
    expect(formatIsoDate('2026-02-29')).toBe('2026-02-29');
    expect(formatIsoDate('1900-02-29')).toBe('1900-02-29');
  });

  it('Assumption 2: year 0000 is returned untouched rather than shown as "1 January 0"', () => {
    expect(formatIsoDate('0000-01-01')).toBe('0000-01-01');
  });
});
