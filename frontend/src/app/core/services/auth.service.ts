import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { LoginRequest, LoginResponse } from '../models/auth.model';

/**
 * FR-1.1, FR-1.3: Authentication service.
 *
 * JWT is stored in localStorage. Trade-off acknowledged: localStorage is
 * susceptible to XSS. For this single-user HR tool the risk is accepted;
 * a production hardening step would be httpOnly cookies with a backend
 * /token/refresh endpoint. See NFR-4.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private static readonly TOKEN_KEY = 'acme_jwt';
  private static readonly BASE = '/api/v1';

  constructor(private http: HttpClient) {}

  /** FR-1.1: POST /auth/login */
  login(email: string, password: string): Observable<LoginResponse> {
    const body: LoginRequest = { email, password };
    return this.http
      .post<LoginResponse>(`${AuthService.BASE}/auth/login`, body)
      .pipe(tap(res => localStorage.setItem(AuthService.TOKEN_KEY, res.accessToken)));
  }

  logout(): void {
    localStorage.removeItem(AuthService.TOKEN_KEY);
  }

  isLoggedIn(): boolean {
    return !!this.getToken();
  }

  getToken(): string | null {
    return localStorage.getItem(AuthService.TOKEN_KEY);
  }
}
