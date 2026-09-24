import { HttpErrorResponse } from '@angular/common/http';
import { problemCode, problemMessage } from './problem';

// requirements.md section 7: every error is RFC 7807 problem+json with a stable `code`. The UI
// shows the server's `detail` (or the per-field messages) and branches on `code`, never on text.
describe('problem helpers', () => {
  const problem = (body: unknown, status = 400) =>
    new HttpErrorResponse({ status, error: body });

  it('FR-2.6 / FR-3.2: problemMessage shows the server problem detail when present (requirements.md section 7)', () => {
    const err = problem({ detail: 'Exchange rate unavailable', code: 'EXCHANGE_RATE_UNAVAILABLE' }, 409);
    expect(problemMessage(err, 'fallback')).toBe('Exchange rate unavailable');
  });

  it('FR-3.2: problemMessage prefers per-field validation errors and names the field (requirements.md section 7)', () => {
    const err = problem({
      detail: 'Request validation failed',
      code: 'VALIDATION_FAILED',
      errors: [{ field: 'effectiveFrom', message: 'must not be null' }],
    });
    expect(problemMessage(err, 'fallback')).toBe('effectiveFrom: must not be null');
  });

  it('FR-2.6 / FR-3.2: problemMessage falls back to the caller text when the body is not a problem document (section 7)', () => {
    expect(problemMessage(problem('<html>gateway</html>', 502), 'Something failed')).toBe('Something failed');
    expect(problemMessage(new Error('boom'), 'Something failed')).toBe('Something failed');
  });

  it('FR-2.6: problemCode returns the stable code (CONCURRENT_UPDATE), or null when absent (section 7)', () => {
    expect(problemCode(problem({ code: 'CONCURRENT_UPDATE' }, 409))).toBe('CONCURRENT_UPDATE');
    expect(problemCode(problem({ detail: 'x' }, 500))).toBeNull();
    expect(problemCode('nope')).toBeNull();
  });
});
