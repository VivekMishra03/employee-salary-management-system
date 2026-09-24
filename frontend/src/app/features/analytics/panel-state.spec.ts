import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';
import { PanelState, panelState } from './panel-state';

describe('panelState', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  /** Each fetch call gets its own Subject so the test decides when, and in what order, responses arrive. */
  function harness() {
    const requests: { param: string; response: Subject<string> }[] = [];
    const param = signal('a');
    const state = TestBed.runInInjectionContext(() =>
      panelState(param, p => {
        const response = new Subject<string>();
        requests.push({ param: p, response });
        return response as Observable<string>;
      }, 'Could not load.'));
    return { requests, param, state };
  }

  const kind = (s: PanelState<string>): string => s.status;

  it('FR-4.7: starts loading, fetches for the current params, and becomes ready with the response', () => {
    const { requests, state } = harness();
    expect(kind(state())).toBe('loading');

    TestBed.tick();
    expect(requests.map(r => r.param)).toEqual(['a']);
    expect(kind(state())).toBe('loading');

    requests[0].response.next('data-a');
    expect(state()).toEqual({ status: 'ready', data: 'data-a' });
  });

  it('FR-4.7: changed params fetch again and show loading until the new response arrives', () => {
    const { requests, param, state } = harness();
    TestBed.tick();
    requests[0].response.next('data-a');

    param.set('b');
    TestBed.tick();

    expect(requests.map(r => r.param)).toEqual(['a', 'b']);
    expect(kind(state())).toBe('loading');
    requests[1].response.next('data-b');
    expect(state()).toEqual({ status: 'ready', data: 'data-b' });
  });

  it('FR-4.7: a response to superseded params arriving late is ignored', () => {
    const { requests, param, state } = harness();
    TestBed.tick();
    param.set('b');
    TestBed.tick();

    requests[1].response.next('data-b');
    requests[0].response.next('stale-a'); // its subscription was abandoned, so this must go nowhere

    expect(state()).toEqual({ status: 'ready', data: 'data-b' });
  });

  it('FR-4.7: a failed fetch becomes an error state carrying the API message, not a thrown error', () => {
    const { requests, state } = harness();
    TestBed.tick();

    requests[0].response.error(new HttpErrorResponse({
      status: 400, error: { code: 'TREND_SPAN_TOO_LARGE', detail: 'the range spans more than 120 periods' },
    }));

    expect(state()).toEqual({ status: 'error', message: 'the range spans more than 120 periods' });
  });

  it('FR-4.7: an error without a message falls back to the given text', () => {
    const { requests, state } = harness();
    TestBed.tick();

    requests[0].response.error(new HttpErrorResponse({ status: 500 }));

    expect(state()).toEqual({ status: 'error', message: 'Could not load.' });
  });

  it('FR-4.7: after an error, changed params load again (the failure does not end the stream)', () => {
    const { requests, param, state } = harness();
    TestBed.tick();
    requests[0].response.error(new HttpErrorResponse({ status: 500 }));

    param.set('b');
    TestBed.tick();
    expect(kind(state())).toBe('loading');
    requests[1].response.next('data-b');

    expect(state()).toEqual({ status: 'ready', data: 'data-b' });
  });
});
