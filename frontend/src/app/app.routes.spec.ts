import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';
import { detail } from '../testing/fixtures';

// FR-1.3: no salary-bearing page may be reachable without a session. authGuard has its own spec;
// these check that it is actually attached to every such route, which the guard spec cannot see.
describe('app routes', () => {
  describe('configuration', () => {
    const shell = routes.find(r => r.path === '');

    it('FR-1.3: the shell route is protected by authGuard', () => {
      expect(shell).toBeDefined();
      expect(shell!.canActivate).toContain(authGuard);
    });

    it('FR-1.3: the employee list and the employee detail are children of the protected shell', () => {
      const childPaths = (shell!.children ?? []).map(c => c.path);
      expect(childPaths).toContain('employees');
      expect(childPaths).toContain('employees/:id');
    });

    it('FR-1.3 / FR-4: the analytics dashboard is a child of the protected shell, so it inherits the guard', () => {
      const analytics = (shell!.children ?? []).find(c => c.path === 'analytics');
      expect(analytics).toBeDefined();
      expect(analytics!.loadComponent).toBeDefined();
      expect(routes.filter(r => r !== shell && r.path === 'analytics')).toEqual([]);
    });

    it('FR-1.3: no route outside the shell other than login serves a page', () => {
      const outside = routes.filter(r => r !== shell).map(r => r.path);
      expect(outside).toEqual(['login', '**']);
      expect(routes.find(r => r.path === '**')!.redirectTo).toBe('employees');
    });

    it('FR-1.1: the login route has no guard, or nobody could ever sign in', () => {
      const login = routes.find(r => r.path === 'login');
      expect(login).toBeDefined();
      expect(login!.canActivate).toBeUndefined();
    });
  });

  describe('navigation', () => {
    let http: HttpTestingController;

    beforeEach(() => {
      localStorage.clear();
      TestBed.configureTestingModule({
        providers: [
          provideZonelessChangeDetection(),
          provideRouter(routes),
          provideHttpClient(),
          provideHttpClientTesting(),
        ],
      });
      http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
      localStorage.clear();
      http.verify();
    });

    it('FR-1.3: with no token, opening an employee URL directly ends at /login and requests no employee data', async () => {
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/employees/42');

      expect(TestBed.inject(Router).url).toBe('/login');
      http.expectNone('/api/v1/employees/42');
    });

    it('FR-1.3: with no token, the list URL and the bare root also end at /login', async () => {
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/employees');
      expect(TestBed.inject(Router).url).toBe('/login');

      await harness.navigateByUrl('/');
      expect(TestBed.inject(Router).url).toBe('/login');
    });

    it('FR-1.3 / FR-4: with no token, opening /analytics directly ends at /login and requests no analytics data', async () => {
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/analytics');

      expect(TestBed.inject(Router).url).toBe('/login');
      http.expectNone(r => r.url.startsWith('/api/v1/analytics'));
    });

    it('FR-1.3 / FR-4: with a token, /analytics is reached and asks for its data (so the redirect above is the guard, not a missing route)', async () => {
      localStorage.setItem('acme_jwt', 'jwt-1');
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/analytics');

      expect(TestBed.inject(Router).url).toBe('/analytics');
      expect(http.match(r => r.url === '/api/v1/analytics/summary').length).toBe(1);
      http.match(() => true); // the other panels' and the filters' requests are not this test's concern
    });

    it('FR-1.3: with a token, the same employee URL is reached (so the redirect above is the guard, not a broken route)', async () => {
      localStorage.setItem('acme_jwt', 'jwt-1');
      const harness = await RouterTestingHarness.create();

      await harness.navigateByUrl('/employees/42');
      http.expectOne('/api/v1/employees/42').flush(detail());

      expect(TestBed.inject(Router).url).toBe('/employees/42');
    });
  });
});
