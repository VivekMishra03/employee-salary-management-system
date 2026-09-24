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
