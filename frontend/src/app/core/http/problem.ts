import { HttpErrorResponse } from '@angular/common/http';

/** RFC 7807 body as produced by the API's GlobalExceptionHandler (requirements.md section 7). */
export interface ProblemDetail {
  status?: number;
  title?: string;
  detail?: string;
  code?: string;
  errors?: { field: string; message: string }[];
}

function asProblem(err: unknown): ProblemDetail | null {
  if (!(err instanceof HttpErrorResponse)) {
    return null;
  }
  const body: unknown = err.error;
  return body !== null && typeof body === 'object' ? (body as ProblemDetail) : null;
}

/** The stable machine-readable code, for branching (e.g. CONCURRENT_UPDATE), or null. */
export function problemCode(err: unknown): string | null {
  return asProblem(err)?.code ?? null;
}

/**
 * A human-readable message for a snackbar. Per-field validation errors win over the generic
 * detail because "effectiveFrom: must not be null" is actionable and "Request validation failed"
 * is not.
 */
export function problemMessage(err: unknown, fallback: string): string {
  const problem = asProblem(err);
  if (problem?.errors?.length) {
    return problem.errors.map(e => `${e.field}: ${e.message}`).join('; ');
  }
  return problem?.detail ?? fallback;
}
