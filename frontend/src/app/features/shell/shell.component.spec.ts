import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/services/auth.service';
import { asRgb, paletteVar } from '../../../testing/palette';

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

  // Look and feel: the requested blue-and-grey gradient must not silently disappear.
  it('the content area has a blue-to-grey gradient background that does not repeat', () => {
    const style = getComputedStyle(fixture.nativeElement.querySelector('.shell-main') as HTMLElement);

    expect(style.backgroundImage).toContain('linear-gradient');
    expect(style.backgroundImage).toContain(asRgb(paletteVar('--acme-page-start')));
    expect(style.backgroundImage).toContain(asRgb(paletteVar('--acme-page-end')));
    expect(style.backgroundRepeat).toBe('no-repeat');
  });

  // The content area is itself the scroll container and exactly one viewport tall, so a background
  // attached to it stays put while its content scrolls; no `fixed` attachment is needed for that.
  it('the gradient stays still while a long page scrolls inside the content area', () => {
    const main = fixture.nativeElement.querySelector('.shell-main') as HTMLElement;
    const filler = document.createElement('div');
    filler.style.height = '3000px';
    fixture.nativeElement.querySelector('.shell-content').appendChild(filler);
    const before = main.getBoundingClientRect();

    main.scrollTop = 200;
    const after = main.getBoundingClientRect();
    const style = getComputedStyle(main);

    expect(main.scrollTop).toBe(200);
    expect(main.clientHeight).toBe(window.innerHeight);
    expect(after.top).toBe(before.top);
    expect(after.height).toBe(before.height);
    expect(style.backgroundAttachment).toBe('scroll');
  });

  it('the toolbar has a deep blue gradient running from its darkest to its lightest colour', () => {
    const style = getComputedStyle(fixture.nativeElement.querySelector('mat-toolbar') as HTMLElement);

    expect(style.backgroundImage).toContain('linear-gradient');
    expect(style.backgroundImage).toContain(asRgb(paletteVar('--acme-toolbar-start')));
    expect(style.backgroundImage).toContain(asRgb(paletteVar('--acme-toolbar-end')));
    expect(style.color).toBe(asRgb(paletteVar('--acme-toolbar-text')));
  });
});
