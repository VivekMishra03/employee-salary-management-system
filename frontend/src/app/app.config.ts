import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { RouteReuseStrategy, provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { ParamAwareRouteReuseStrategy } from './core/routing/param-aware-route-reuse.strategy';

// Zoneless on purpose (docs/adr/0004): zone.js is not a dependency, and Angular Material 21 no
// longer needs @angular/animations, so neither provider appears here.
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    { provide: RouteReuseStrategy, useClass: ParamAwareRouteReuseStrategy },
    // FR-1.2 / FR-1.3: the interceptor is the only place a token is attached.
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
