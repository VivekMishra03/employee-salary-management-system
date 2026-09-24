/** Reads `#rgb` or `#rrggbb` (any case, surrounding whitespace allowed) into 0-255 channels. */
export function parseHexColor(value: string): [number, number, number] {
  const match = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value.trim());
  if (!match) {
    throw new Error(`Not a hex colour: "${value}"`);
  }
  const digits = match[1].length === 3 ? [...match[1]].map(d => d + d).join('') : match[1];
  return [0, 2, 4].map(i => parseInt(digits.slice(i, i + 2), 16)) as [number, number, number];
}

/** WCAG 2.x relative luminance: 0 for black, 1 for white. */
export function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHexColor(hex).map(channel => {
    const c = channel / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.x contrast ratio between two colours, from 1 (identical) to 21 (black on white). */
export function contrastRatio(a: string, b: string): number {
  const [la, lb] = [relativeLuminance(a), relativeLuminance(b)];
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}
