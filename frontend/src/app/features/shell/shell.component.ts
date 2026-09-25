import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../core/services/auth.service';
import { HandsetService } from '../../core/layout/handset.service';

/** FR-1.3: authenticated app shell — toolbar with logout, sidenav, content outlet. */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  // FR-5 (import/export) was dropped (ADR-0012); nothing here may point at a screen that does not exist.
  protected readonly navItems = [
    { label: 'Employees', icon: 'people', route: '/employees' },
    { label: 'Analytics', icon: 'insights', route: '/analytics' },
  ];

  protected readonly handset = inject(HandsetService).handset;

  // Deliberately not persisted: the menu starts open beside the content on a desktop and closed over it on a phone.
  protected readonly navOpen = signal(!this.handset());

  constructor() {
    // Rotating a tablet or resizing a window re-applies the default for the new size; it never runs on a toggle,
    // because it reads only `handset`.
    effect(() => this.navOpen.set(!this.handset()));
  }

  toggleNav(): void {
    this.navOpen.update(open => !open);
  }

  // On a phone the drawer covers the page, so choosing a destination must uncover it.
  protected onNavigate(): void {
    if (this.handset()) {
      this.navOpen.set(false);
    }
  }

  // Material's own Escape handler (registered first, so it has already run) closes the drawer synchronously, and the
  // closed drawer turns visibility:hidden. Chrome then blurs anything focused inside it at the next rendered frame, which
  // can land before the setTimeout that emits (openedChange), leaving focus on <body> for that handler to find. So hand
  // focus to the toggle in the same task as the keypress, while the link is still focused.
  protected onNavEscape(): void {
    this.focusToggleIfFocusInsideNav();
  }

  // Material also closes the drawer itself (Escape); (closedStart) keeps the signal in step so aria-expanded
  // does not lie. Focus that was inside the closing drawer would otherwise fall back to <body>; hand it to the toggle.
  protected onNavOpenedChange(open: boolean): void {
    if (!open) {
      this.focusToggleIfFocusInsideNav();
    }
  }

  private focusToggleIfFocusInsideNav(): void {
    const active = document.activeElement;
    if (active && document.getElementById('shell-nav')?.contains(active)) {
      document.getElementById('nav-toggle')?.focus();
    }
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
