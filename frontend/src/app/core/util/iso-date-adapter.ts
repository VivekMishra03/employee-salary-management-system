import { Injectable } from '@angular/core';
import { NativeDateAdapter } from '@angular/material/core';

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * FR-3.6: the native adapter reads a typed date-only ISO string ("2026-10-01") through Date.parse,
 * which treats it as UTC midnight: a calendar day early in every zone west of UTC. A date the user
 * types is a calendar day, so a strict yyyy-MM-dd is built as local midnight instead. Every other
 * format still goes to the base adapter.
 */
@Injectable()
export class IsoDateAdapter extends NativeDateAdapter {
  override parse(value: unknown, parseFormat?: unknown): Date | null {
    if (typeof value !== 'string') {
      return super.parse(value, parseFormat);
    }
    const match = ISO_DATE.exec(value.trim());
    if (!match) {
      return super.parse(value, parseFormat);
    }
    const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
    // setFullYear rather than the Date constructor, which maps years 0-99 to 1900-1999.
    const date = new Date(2000, 0, 1);
    date.setFullYear(year, month - 1, day);
    // A day that does not exist (30 February) rolls into the next month; that is refused, not accepted.
    const exists = date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
    return exists ? date : this.invalid();
  }
}
