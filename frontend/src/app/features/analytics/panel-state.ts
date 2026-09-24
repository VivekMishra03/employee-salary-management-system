import { Signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of, startWith, switchMap } from 'rxjs';
import { problemMessage } from '../../core/http/problem';

/** FR-4.7: every dashboard panel is exactly one of these, so one failing endpoint never blanks the others. */
export type PanelState<T> =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: T };

/**
 * Re-runs `fetch` whenever `params` changes and exposes the outcome as a signal. switchMap abandons the
 * in-flight request of superseded params, so a slow answer to an old filter can never overwrite the
 * answer to the current one; the catchError sits inside it so a failure ends that request, not the stream.
 * Must be called in an injection context (a field initialiser).
 */
export function panelState<P, T>(
  params: Signal<P>,
  fetch: (params: P) => Observable<T>,
  fallbackMessage: string,
): Signal<PanelState<T>> {
  const loading: PanelState<T> = { status: 'loading' };
  return toSignal(
    toObservable(params).pipe(
      switchMap(p =>
        fetch(p).pipe(
          map((data): PanelState<T> => ({ status: 'ready', data })),
          catchError(err => of<PanelState<T>>({ status: 'error', message: problemMessage(err, fallbackMessage) })),
          startWith(loading),
        )),
    ),
    { initialValue: loading },
  );
}
