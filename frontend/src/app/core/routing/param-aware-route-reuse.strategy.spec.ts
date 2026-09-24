import { ActivatedRouteSnapshot, Route } from '@angular/router';
import { ParamAwareRouteReuseStrategy } from './param-aware-route-reuse.strategy';

describe('ParamAwareRouteReuseStrategy', () => {
  const detailRoute: Route = { path: 'employees/:id' };
  const listRoute: Route = { path: 'employees' };
  const snapshot = (routeConfig: Route, params: Record<string, string> = {}) =>
    ({ routeConfig, params }) as unknown as ActivatedRouteSnapshot;
  const strategy = new ParamAwareRouteReuseStrategy();

  it('FR-2.5: moving from one employee to another (manager / direct-report link) rebuilds the page', () => {
    expect(strategy.shouldReuseRoute(snapshot(detailRoute, { id: '7' }), snapshot(detailRoute, { id: '42' }))).toBeFalse();
  });

  it('FR-2.5: the same employee route with the same id is reused', () => {
    expect(strategy.shouldReuseRoute(snapshot(detailRoute, { id: '42' }), snapshot(detailRoute, { id: '42' }))).toBeTrue();
  });

  it('FR-2.5: different routes are never reused', () => {
    expect(strategy.shouldReuseRoute(snapshot(listRoute), snapshot(detailRoute, { id: '42' }))).toBeFalse();
  });
});
