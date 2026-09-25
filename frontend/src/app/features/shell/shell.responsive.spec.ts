import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { provideRouter } from '@angular/router';
import { BehaviorSubject, map } from 'rxjs';
import { ShellComponent } from './shell.component';
import { expectNoHorizontalOverflow, setHostWidth } from '../../../testing/layout';

@Component({ template: '' })
class BlankComponent {}

// Section 4 (responsive web): on a phone the navigation is a drawer over the content, not a column beside it.
describe('ShellComponent on narrow and wide screens', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let handset$: BehaviorSubject<boolean>;
  let queries: string[];

  const toggle = () => fixture.nativeElement.querySelector('#nav-toggle') as HTMLButtonElement;
  const sidenav = () => fixture.nativeElement.querySelector('mat-sidenav') as HTMLElement;
  const backdrop = () => fixture.nativeElement.querySelector('.mat-drawer-backdrop') as HTMLElement | null;
  const settle = async () => { await fixture.whenStable(); await new Promise(r => requestAnimationFrame(r)); fixture.detectChanges(); await fixture.whenStable(); };

  async function create(handset: boolean): Promise<void> {
    handset$ = new BehaviorSubject(handset);
    queries = [];
    const fake = {
      observe: (query: string): unknown => {
        queries.push(query);
        return handset$.pipe(map((matches): BreakpointState => ({ matches, breakpoints: { [query]: matches } })));
      },
    };
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: '**', component: BlankComponent }]),
        { provide: BreakpointObserver, useValue: fake },
        { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ShellComponent);
    await settle();
  }

  afterEach(() => localStorage.clear());

  describe('handset', () => {
    beforeEach(() => create(true));

    it('a handset is a viewport of at most 767.98px', () => {
      expect(queries).toContain('(max-width: 767.98px)');
    });

    it('the navigation drawer starts closed and floats over the content', () => {
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
      expect(sidenav().classList).toContain('mat-drawer-over');
      expect(sidenav().classList).not.toContain('mat-drawer-side');
      expect(sidenav().getBoundingClientRect().right).toBeLessThanOrEqual(0);
    });

    it('the content is full width whether the drawer is closed or open', async () => {
      const main = fixture.nativeElement.querySelector('.shell-main') as HTMLElement;
      const container = fixture.nativeElement.querySelector('mat-sidenav-container') as HTMLElement;
      expect(main.getBoundingClientRect().left).toBe(0);
      expect(main.getBoundingClientRect().width).toBe(container.getBoundingClientRect().width);

      toggle().click();
      await settle();

      expect(main.getBoundingClientRect().left).toBe(0);
      expect(main.getBoundingClientRect().width).toBe(container.getBoundingClientRect().width);
    });

    it('opening the drawer shows a backdrop, and clicking the backdrop closes it', async () => {
      expect(backdrop()?.classList.contains('mat-drawer-shown')).toBeFalsy();

      toggle().click();
      await settle();
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(backdrop()?.classList).toContain('mat-drawer-shown');

      backdrop()!.click();
      await settle();
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
      expect(backdrop()?.classList.contains('mat-drawer-shown')).toBeFalsy();
    });

    it('choosing a navigation link closes the drawer', async () => {
      toggle().click();
      await settle();
      expect(toggle().getAttribute('aria-expanded')).toBe('true');

      (sidenav().querySelector('a') as HTMLElement).click();
      await settle();

      expect(toggle().getAttribute('aria-expanded')).toBe('false');
    });

    it('navigation links are at least 44px tall', async () => {
      toggle().click();
      await settle();

      for (const link of Array.from(sidenav().querySelectorAll('a'))) {
        expect(link.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
      }
    });

    it('content padding is 12px', () => {
      expect(getComputedStyle(fixture.nativeElement.querySelector('.shell-content')).paddingLeft).toBe('12px');
    });

    it('at 360px nothing overflows and the logout button is reachable', () => {
      const host = setHostWidth(fixture, 360);
      const logout = fixture.nativeElement.querySelector('#logout-btn') as HTMLElement;

      expectNoHorizontalOverflow(host);
      expect(logout.getBoundingClientRect().right).toBeLessThanOrEqual(host.getBoundingClientRect().right);
      expect(logout.getBoundingClientRect().left).toBeGreaterThanOrEqual(0);
    });

    it('at 280px the title is cut with an ellipsis and the logout button keeps its place', () => {
      const host = setHostWidth(fixture, 280);
      const title = fixture.nativeElement.querySelector('.shell-title') as HTMLElement;
      const logout = fixture.nativeElement.querySelector('#logout-btn') as HTMLElement;

      expect(getComputedStyle(title).textOverflow).toBe('ellipsis');
      expect(title.scrollWidth).toBeGreaterThan(title.clientWidth);
      expect(title.getBoundingClientRect().right).toBeLessThanOrEqual(logout.getBoundingClientRect().left);
      expectNoHorizontalOverflow(host);
    });

    it('switching to a wide screen opens the drawer beside the content, and back closes it', async () => {
      handset$.next(false);
      await settle();
      expect(sidenav().classList).toContain('mat-drawer-side');
      expect(toggle().getAttribute('aria-expanded')).toBe('true');

      handset$.next(true);
      await settle();
      expect(sidenav().classList).toContain('mat-drawer-over');
      expect(toggle().getAttribute('aria-expanded')).toBe('false');
    });
  });

  describe('desktop', () => {
    beforeEach(() => create(false));

    it('the drawer is open beside the content, with no backdrop', () => {
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(sidenav().classList).toContain('mat-drawer-side');
      expect(backdrop()?.classList.contains('mat-drawer-shown')).toBeFalsy();
      expect((fixture.nativeElement.querySelector('.shell-main') as HTMLElement).getBoundingClientRect().left).toBeGreaterThan(0);
    });

    it('choosing a navigation link leaves the drawer open', async () => {
      (sidenav().querySelector('a') as HTMLElement).click();
      await settle();

      expect(toggle().getAttribute('aria-expanded')).toBe('true');
    });

    it('content padding is 24px', () => {
      expect(getComputedStyle(fixture.nativeElement.querySelector('.shell-content')).paddingLeft).toBe('24px');
    });
  });
});
