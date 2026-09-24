import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * FR-1.2, FR-1.3: Attaches JWT bearer token to every request that is not
 * the login endpoint itself. On a 401 response, clears the stored token
 * and redirects to /login so the user cannot see stale salary data.
 *
 * This is the ONLY place a token is attached — CLAUDE.md §Angular rule.
 */
export const authInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn
) => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  // Never attach token to the login endpoint itself
  if (req.url.includes('/auth/login')) {
    return next(req);
  }

  const token = authService.getToken();
  const cloned = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(cloned).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401) {
        authService.logout();
        router.navigate(['/login']); // FR-1.3
      }
      return throwError(() => err);
    })
  );
};
