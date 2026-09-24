import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('FR-1.1: login posts email and password to /api/v1/auth/login and stores the access token', () => {
    let completed = false;
    service.login('hr@acme.com', 'secret').subscribe(() => (completed = true));

    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'hr@acme.com', password: 'secret' });
    req.flush({ accessToken: 'jwt-123', tokenType: 'Bearer', expiresAt: '2026-09-23T12:00:00Z' });

    expect(completed).toBeTrue();
    expect(service.getToken()).toBe('jwt-123');
    expect(service.isLoggedIn()).toBeTrue();
  });

  it('FR-1.1: a failed login stores nothing', () => {
    service.login('hr@acme.com', 'wrong').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/auth/login').flush({ code: 'INVALID_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });

    expect(service.getToken()).toBeNull();
    expect(service.isLoggedIn()).toBeFalse();
  });

  it('FR-1.3: logout discards the token', () => {
    localStorage.setItem('acme_jwt', 'jwt-123');
    expect(service.isLoggedIn()).toBeTrue();

    service.logout();

    expect(service.getToken()).toBeNull();
    expect(service.isLoggedIn()).toBeFalse();
  });
});
