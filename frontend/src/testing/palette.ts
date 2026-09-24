import { parseHexColor } from '../app/core/util/contrast';

/** The value of a palette custom property as the browser resolved it (styles.scss is loaded by the Karma config). */
export function paletteVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** A palette hex colour written the way `getComputedStyle` reports colours, e.g. `rgb(27, 86, 165)`. */
export function asRgb(hex: string): string {
  const [r, g, b] = parseHexColor(hex);
  return `rgb(${r}, ${g}, ${b})`;
}
