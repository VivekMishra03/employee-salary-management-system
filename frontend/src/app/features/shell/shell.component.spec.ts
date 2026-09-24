import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/services/auth.service';

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let router: Router;

  beforeEach(async () => {
    localStorage.setItem('acme_jwt', 'jwt-1');
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    }).compileComponents();
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(ShellComponent);
    await fixture.whenStable();
  });

  afterEach(() => localStorage.clear());

  // ADR-0012: import/export (FR-5) is dropped, so the two screens the API serves are the whole menu.
  it('FR-2.1 / FR-4: navigation offers the employee directory and the analytics dashboard, and nothing else', () => {
    const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-nav-list a'))
      .map(a => (a as HTMLElement).textContent?.trim());
    expect(labels).toEqual(['Employees', 'Analytics']);
  });

  it('FR-1.3: logout discards the token and returns to the login page', () => {
    (fixture.nativeElement.querySelector('#logout-btn') as HTMLButtonElement).click();

    expect(TestBed.inject(AuthService).getToken()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
