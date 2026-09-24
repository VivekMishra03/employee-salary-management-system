import { ActivatedRouteSnapshot, BaseRouteReuseStrategy } from '@angular/router';

/**
 * By default the router reuses a component when only its route parameters change. The employee page
 * reads its id once, so following a manager or direct-report link (employees/7 to employees/42)
 * would keep showing the previous person. Rebuilding on a parameter change makes that link work.
 */
export class ParamAwareRouteReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(future: ActivatedRouteSnapshot, current: ActivatedRouteSnapshot): boolean {
    return super.shouldReuseRoute(future, current) && sameParams(future.params, current.params);
  }
}

function sameParams(a: Record<string, string>, b: Record<string, string>): boolean {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(key => a[key] === b[key]);
}
