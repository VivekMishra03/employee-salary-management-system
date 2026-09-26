import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { provideRouter } from '@angular/router';
import { BehaviorSubject, map } from 'rxjs';
import { ShellComponent } from './shell.component';

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
  });
});
