import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('FR-1.2: attaches the stored token as a Bearer header on API requests', () => {
    localStorage.setItem('acme_jwt', 'jwt-123');
    client.get('/api/v1/employees').subscribe();

    const req = http.expectOne('/api/v1/employees');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-123');
    req.flush({});
  });

  it('FR-1.2: sends no Authorization header when there is no token', () => {
    client.get('/api/v1/employees').subscribe({ error: () => undefined });

    const req = http.expectOne('/api/v1/employees');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({}, { status: 401, statusText: 'Unauthorized' });
  });

  it('FR-1.1: never attaches a (stale) token to the login request itself', () => {
    localStorage.setItem('acme_jwt', 'stale');
    client.post('/api/v1/auth/login', {}).subscribe();

    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('FR-1.3: a 401 discards the token and redirects to /login', () => {
    localStorage.setItem('acme_jwt', 'expired');
    let status = 0;
    client.get('/api/v1/employees/1').subscribe({ error: e => (status = e.status) });

    http.expectOne('/api/v1/employees/1').flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });

    expect(status).toBe(401);
    expect(TestBed.inject(AuthService).getToken()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('FR-1.3: other errors keep the session (a 409 is not a sign-out)', () => {
    localStorage.setItem('acme_jwt', 'jwt-123');
    client.put('/api/v1/employees/1', {}).subscribe({ error: () => undefined });

    http.expectOne('/api/v1/employees/1').flush({ code: 'CONCURRENT_UPDATE' }, { status: 409, statusText: 'Conflict' });

    expect(TestBed.inject(AuthService).getToken()).toBe('jwt-123');
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
