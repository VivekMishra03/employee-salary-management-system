import { Injectable, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BreakpointObserver } from '@angular/cdk/layout';
import { map } from 'rxjs';

/** Below the width of a portrait tablet (768px). Matches the shell's own idea of a phone, and nothing else uses it. */
export const HANDSET_QUERY = '(max-width: 767.98px)';

/**
 * Section 4 (responsive web): whether the viewport is a phone. Only the shell needs this (its drawer changes mode); every page layout
 * responds to its own container width in CSS instead. Wrapped in a service so a spec can stand in for the browser's window.
 */
@Injectable({ providedIn: 'root' })
export class HandsetService {
  // requireSync: BreakpointObserver emits the current state on subscribe, so there is never an unknown first value.
  readonly handset = toSignal(
    inject(BreakpointObserver).observe(HANDSET_QUERY).pipe(map(state => state.matches)),
    { requireSync: true },
  );
}
