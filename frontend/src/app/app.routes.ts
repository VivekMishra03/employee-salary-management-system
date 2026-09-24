import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

// FR-5 (import/export) was dropped from scope (ADR-0012), so no route points at it and nothing in the
// UI promises what the API does not serve.
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent),
  },
  {
    // FR-1.3: every salary-bearing page sits behind the guard.
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell.component').then(m => m.ShellComponent),
    children: [
      { path: '', redirectTo: 'employees', pathMatch: 'full' },
      {
        path: 'employees',
        loadComponent: () =>
          import('./features/employees/employee-list/employee-list.component').then(m => m.EmployeeListComponent),
      },
      {
        path: 'employees/:id',
        loadComponent: () =>
          import('./features/employees/employee-detail/employee-detail.component').then(m => m.EmployeeDetailComponent),
      },
      {
        // FR-4: the analytics dashboard.
        path: 'analytics',
        loadComponent: () =>
          import('./features/analytics/analytics.component').then(m => m.AnalyticsComponent),
      },
    ],
  },
  { path: '**', redirectTo: 'employees' },
];
