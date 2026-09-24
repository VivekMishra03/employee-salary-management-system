import { contrastRatio, parseHexColor, relativeLuminance } from './contrast';

// WCAG 2.x relative luminance and contrast ratio, used by the palette spec (accessibility of the look and feel).
describe('contrast', () => {
  it('relativeLuminance: black is 0 and white is 1', () => {
    expect(relativeLuminance('#000000')).toBe(0);
    expect(relativeLuminance('#ffffff')).toBe(1);
  });

  it('contrastRatio: black on white is the maximum, 21 to 1', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
  });

  it('contrastRatio: #767676 on white is the well-known 4.54, the darkest grey that passes AA for normal text', () => {
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 2);
  });

  it('contrastRatio: #777777 on white is 4.48, just under AA for normal text', () => {
    expect(contrastRatio('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
  });

  it('contrastRatio: identical colours are 1 to 1', () => {
    expect(contrastRatio('#1e5aa8', '#1e5aa8')).toBe(1);
  });

  it('contrastRatio: does not depend on which colour is the foreground', () => {
    expect(contrastRatio('#ffffff', '#767676')).toBe(contrastRatio('#767676', '#ffffff'));
  });

  it('parseHexColor: reads six-digit and three-digit hex, upper or lower case, ignoring surrounding whitespace', () => {
    expect(parseHexColor(' #1E5AA8 ')).toEqual([30, 90, 168]);
    expect(parseHexColor('#fa0')).toEqual([255, 170, 0]);
  });

  it('parseHexColor: rejects anything that is not a hex colour, so a palette typo fails loudly instead of passing as NaN', () => {
    expect(() => parseHexColor('rgb(0, 0, 0)')).toThrowError(/hex colour/);
    expect(() => parseHexColor('#12')).toThrowError(/hex colour/);
    expect(() => parseHexColor('')).toThrowError(/hex colour/);
  });
});
