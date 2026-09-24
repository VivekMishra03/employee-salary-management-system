import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/services/auth.service';
import { asRgb, paletteVar } from '../../../testing/palette';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let http: HttpTestingController;
  let router: Router;
  let snack: jasmine.Spy;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    snack = spyOn(TestBed.inject(MatSnackBar), 'open').and.stub();
    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('FR-1.1: an empty or malformed form cannot be submitted', async () => {
    component.form.setValue({ email: 'not-an-email', password: '' });
    await fixture.whenStable();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('#login-submit');
    expect(button.disabled).toBeTrue();

    component.submit();
    http.expectNone('/api/v1/auth/login');
  });

  it('FR-1.1: valid credentials are posted, the token is kept and the user lands on the directory', async () => {
    component.form.setValue({ email: 'hr.manager@acme.com', password: 'pw' });
    component.submit();

    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.body).toEqual({ email: 'hr.manager@acme.com', password: 'pw' });
    req.flush({ accessToken: 'jwt-1', tokenType: 'Bearer', expiresAt: '2026-09-23T13:00:00Z' });
    await fixture.whenStable();

    expect(TestBed.inject(AuthService).getToken()).toBe('jwt-1');
    expect(router.navigate).toHaveBeenCalledWith(['/employees']);
  });

  it('FR-1.1: a rejected login says so, keeps the user on the page and re-enables the form', async () => {
    component.form.setValue({ email: 'hr.manager@acme.com', password: 'wrong' });
    component.submit();
    expect(component.loading()).toBeTrue();

    http.expectOne('/api/v1/auth/login')
      .flush({ code: 'INVALID_CREDENTIALS', detail: 'Invalid email or password' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(component.loading()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(TestBed.inject(AuthService).getToken()).toBeNull();
    expect(snack).toHaveBeenCalledWith('Invalid email or password.', 'Close', jasmine.anything());
  });

  // Look and feel: the sign-in page shares the blue-to-grey gradient of the rest of the app.
  it('the sign-in page has a fixed blue-to-grey gradient background', () => {
    const style = getComputedStyle(fixture.nativeElement.querySelector('.login-page') as HTMLElement);

    expect(style.backgroundImage).toContain('linear-gradient');
    expect(style.backgroundImage).toContain(asRgb(paletteVar('--acme-page-start')));
    expect(style.backgroundImage).toContain(asRgb(paletteVar('--acme-page-end')));
    expect(style.backgroundAttachment).toBe('fixed');
  });
});
