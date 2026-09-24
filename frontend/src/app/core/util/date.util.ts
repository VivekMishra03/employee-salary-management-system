/**
 * FR-3.6: the calendar date the user picked, as the API's ISO `yyyy-MM-dd`.
 *
 * Deliberately not `toISOString().slice(0, 10)`: that converts to UTC first, so a local-midnight
 * Date in any zone east of UTC lands on the previous day and a raise "effective 1 October" is
 * recorded for 30 September.
 */
export function toIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
] as const;

/**
 * Assumption 2: an API calendar date (`yyyy-MM-dd`) as "24 September 2026". Built from the parts of the
 * text and never through a Date: `new Date('2026-09-24')` is UTC midnight, the 23rd in every zone west
 * of UTC. Text that is not such a date is returned as it came, so a surprise from the server shows as
 * itself instead of as "NaN".
 */
export function formatIsoDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!match) {
    return iso;
  }
  const year = Number(match[1]);
  const monthNumber = Number(match[2]);
  const day = Number(match[3]);
  const month = MONTHS[monthNumber - 1];
  if (year < 1 || month === undefined || day < 1 || day > daysInMonth(year, monthNumber)) {
    return iso;
  }
  return `${day} ${month} ${year}`;
}

/** Gregorian rule, by arithmetic on the parts: no Date is made, so no time zone can move the answer. */
function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
    return leap ? 29 : 28;
  }
  return month === 4 || month === 6 || month === 9 || month === 11 ? 30 : 31;
}
