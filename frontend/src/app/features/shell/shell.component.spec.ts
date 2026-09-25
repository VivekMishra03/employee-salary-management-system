import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { By } from '@angular/platform-browser';
import { firstValueFrom } from 'rxjs';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MatSidenav } from '@angular/material/sidenav';
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
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
      ],
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

  describe('collapsible navigation', () => {
    const toggle = () => fixture.nativeElement.querySelector('#nav-toggle') as HTMLButtonElement;
    const sidenav = () => fixture.nativeElement.querySelector('mat-sidenav') as HTMLElement;
    const main = () => fixture.nativeElement.querySelector('.shell-main') as HTMLElement;
    // Wait on the drawer's own openedChange (emitted once it has finished opening or closing) rather than guessing a
    // number of animation frames; subscribe before acting so the event cannot be missed.
    const drawer = () => fixture.debugElement.query(By.directive(MatSidenav)).componentInstance as MatSidenav;
    const afterDrawerSettles = async (act: () => void) => {
      const changed = firstValueFrom(drawer().openedChange);
      act();
      await fixture.whenStable();
      await changed;
      fixture.detectChanges();
      await fixture.whenStable();
    };
    const click = () => afterDrawerSettles(() => toggle().click());
    const hidden = () => {
      const r = sidenav().getBoundingClientRect();
      return getComputedStyle(sidenav()).visibility === 'hidden' || r.right <= 0 || r.width === 0;
    };

    it('UX: a menu button controls the sidenav and starts expanded with both links visible', () => {
      expect(toggle().tagName).toBe('BUTTON');
      expect(toggle().disabled).toBeFalse();
      expect(toggle().getAttribute('aria-label')).toBe('Toggle navigation menu');
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(toggle().getAttribute('aria-controls')).toBe(sidenav().id);
      expect(sidenav().id).not.toBe('');
      expect(hidden()).toBeFalse();
      expect(sidenav().querySelectorAll('a').length).toBe(2);
    });

    it('UX: clicking the menu button collapses the sidenav and the content moves left', async () => {
      const openLeft = main().getBoundingClientRect().left;

      await click();

      expect(toggle().getAttribute('aria-expanded')).toBe('false');
      expect(hidden()).toBeTrue();
      expect(main().getBoundingClientRect().left).toBeLessThan(openLeft);
    });

    it('UX: clicking again re-opens the sidenav', async () => {
      const openLeft = main().getBoundingClientRect().left;
      await click();
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
      expect(hidden()).toBeTrue();
      await click();

      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(hidden()).toBeFalse();
      expect(main().getBoundingClientRect().left).toBe(openLeft);
    });

    const pressEscapeOn = (el: HTMLElement) => afterDrawerSettles(() =>
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true })));

    it('UX: Escape inside the open sidenav closes it and the button state follows', async () => {
      const link = sidenav().querySelector('a') as HTMLElement;
      link.focus();

      await pressEscapeOn(link);

      expect(toggle().getAttribute('aria-expanded')).toBe('false');
      expect(hidden()).toBeTrue();
    });

    it('UX: after an Escape close, one click on the menu button re-opens the sidenav', async () => {
      const link = sidenav().querySelector('a') as HTMLElement;
      link.focus();
      await pressEscapeOn(link);

      await click();

      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(hidden()).toBeFalse();
    });

    it('UX: Escape from a link inside the sidenav moves focus to the menu button', async () => {
      const link = sidenav().querySelector('a') as HTMLElement;
      link.focus();

      await pressEscapeOn(link);

      expect(document.activeElement).toBe(toggle());
    });

    it('UX: closing with the menu button keeps focus on the menu button', async () => {
      toggle().focus();

      await click();

      expect(document.activeElement).toBe(toggle());
    });

    it('UX: an Escape close never steals focus from a control outside the sidenav', async () => {
      const logout = fixture.nativeElement.querySelector('#logout-btn') as HTMLButtonElement;
      const link = sidenav().querySelector('a') as HTMLElement;
      link.focus();
      logout.focus();

      await pressEscapeOn(link);

      expect(document.activeElement).toBe(logout);
    });

    it('UX: the menu icon stays visible and named while the sidenav is collapsed', async () => {
      await click();
      const rect = toggle().getBoundingClientRect();

      expect(rect.width).toBeGreaterThan(0);
      expect(rect.height).toBeGreaterThan(0);
      expect(rect.left).toBeGreaterThanOrEqual(0);
      expect(getComputedStyle(toggle()).visibility).toBe('visible');
      expect(toggle().querySelector('mat-icon')?.textContent?.trim() || toggle().querySelector('mat-icon')?.getAttribute('fontIcon')).toBe('menu');
    });
  });
});
