import { MonoTypeOperatorFunction, map, switchMap, timer } from 'rxjs';

/**
 * Emits the latest value once `ms` have passed without a newer one (FR-2.3 search, FR-2.5 manager
 * lookup). Built on `timer` rather than rxjs `debounceTime`, which also compares Date.now() against
 * the schedule: a fake timer clock (NFR-3, no real waits in tests) advances the timeout but not the
 * date, so `debounceTime` would silently re-arm itself and never fire.
 */
export function debounceByTimer<T>(ms: number): MonoTypeOperatorFunction<T> {
  return source => source.pipe(switchMap(value => timer(ms).pipe(map(() => value))));
}
