import { toIsoDate } from './date.util';

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
