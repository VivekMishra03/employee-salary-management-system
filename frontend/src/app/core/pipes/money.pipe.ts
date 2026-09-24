import { Pipe, PipeTransform } from '@angular/core';

/**
 * NFR-2: money is formatted, never computed, in the browser. The API hands us NUMERIC(15,2)
 * values already rounded HALF_UP; this pipe only renders them with two decimals and grouping.
 */
@Pipe({ name: 'money', standalone: true })
export class MoneyPipe implements PipeTransform {
  private static readonly FORMAT = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  transform(value: number | null | undefined, currencyCode?: string): string {
    if (value === null || value === undefined) {
      return '—';
    }
    const formatted = MoneyPipe.FORMAT.format(value);
    return currencyCode ? `${formatted} ${currencyCode}` : formatted;
  }
}
