import { InjectionToken } from '@angular/core';

/**
 * NFR-3: the only place the browser clock is read. Everything that needs "today" (the trend's default
 * range) injects this, so a spec provides a fixed date instead of depending on when it runs.
 */
export const TODAY = new InjectionToken<() => Date>('TODAY', {
  providedIn: 'root',
  factory: () => () => new Date(),
});
