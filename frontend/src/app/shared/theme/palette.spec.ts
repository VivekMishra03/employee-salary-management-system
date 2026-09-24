import { contrastRatio } from '../../core/util/contrast';
import { paletteVar } from '../../../testing/palette';

// Accessibility of the look and feel: every colour pairing the app paints text or a chart mark with must
// meet WCAG 2.x AA. The palette is defined once in styles.scss, so this reads it back from the browser
// rather than repeating any hex value here.
const AA_TEXT = 4.5;
const AA_LARGE_TEXT_OR_GRAPHIC = 3;

describe('palette', () => {
  const colour = (name: string): string => paletteVar(name);

  const check = (foreground: string, background: string, minimum: number): void => {
    const ratio = contrastRatio(colour(foreground), colour(background));
    expect(ratio)
      .withContext(`${foreground} (${colour(foreground)}) on ${background} (${colour(background)}) is ${ratio.toFixed(2)}:1`)
      .toBeGreaterThanOrEqual(minimum);
  };

  const TINTS = ['--acme-tint-blue', '--acme-tint-teal', '--acme-tint-violet', '--acme-tint-slate'];
  const PAGE_STOPS = ['--acme-page-start', '--acme-page-mid', '--acme-page-end'];
  const CHIPS = ['below', 'within', 'above', 'no-band', 'danger'].map(n => [`--acme-${n}`, `--acme-${n}-bg`]);
  const ACCENTS = ['blue', 'teal', 'violet', 'amber', 'slate', 'cyan'].map(n => `--acme-accent-${n}`);

  it('every palette colour is defined as a hex value, so a missing or mistyped variable fails here and not on screen', () => {
    const names = [
      '--acme-surface', '--acme-text', '--acme-text-muted', '--acme-link', '--acme-border',
      '--acme-toolbar-start', '--acme-toolbar-end', '--acme-toolbar-text',
      '--acme-chart-from', '--acme-chart-to', '--acme-chart-track', '--acme-series-payroll', '--acme-series-increase',
      ...PAGE_STOPS, ...TINTS, ...ACCENTS, ...CHIPS.flat(),
    ];
    for (const name of names) {
      expect(() => contrastRatio(colour(name), '#ffffff')).withContext(name).not.toThrow();
    }
  });

  it('text and muted text are readable on the white card surface', () => {
    check('--acme-text', '--acme-surface', AA_TEXT);
    check('--acme-text-muted', '--acme-surface', AA_TEXT);
  });

  it('headings are readable on every stop of the page gradient, since they sit directly on it', () => {
    for (const stop of PAGE_STOPS) {
      check('--acme-text', stop, AA_TEXT);
    }
  });

  it('text and muted text are readable on every stat-card tint', () => {
    for (const tint of TINTS) {
      check('--acme-text', tint, AA_TEXT);
      check('--acme-text-muted', tint, AA_TEXT);
    }
  });

  it('chip text is readable on its own chip colour, for every adherence and status chip', () => {
    for (const [text, background] of CHIPS) {
      check(text, background, AA_TEXT);
    }
  });

  it('toolbar text is readable at both the darkest and the lightest end of the toolbar gradient', () => {
    check('--acme-toolbar-text', '--acme-toolbar-start', AA_TEXT);
    check('--acme-toolbar-text', '--acme-toolbar-end', AA_TEXT);
  });

  it('link colour is readable on the card surface and on the blue tint', () => {
    check('--acme-link', '--acme-surface', AA_TEXT);
    check('--acme-link', '--acme-tint-blue', AA_TEXT);
  });

  it('every panel accent (a border and an icon) is visible against the card surface', () => {
    for (const accent of ACCENTS) {
      check(accent, '--acme-surface', AA_LARGE_TEXT_OR_GRAPHIC);
    }
  });

  it('chart marks are visible against the card surface and against the empty bar track', () => {
    for (const mark of ['--acme-chart-from', '--acme-chart-to', '--acme-series-payroll', '--acme-series-increase']) {
      check(mark, '--acme-surface', AA_LARGE_TEXT_OR_GRAPHIC);
    }
    check('--acme-chart-from', '--acme-chart-track', AA_LARGE_TEXT_OR_GRAPHIC);
    check('--acme-chart-to', '--acme-chart-track', AA_LARGE_TEXT_OR_GRAPHIC);
  });

  it('the two trend series are drawn in different colours', () => {
    expect(colour('--acme-series-payroll')).not.toBe(colour('--acme-series-increase'));
  });

  it('the toolbar and the page use a gradient, and the page gradient goes from a blue to a grey', () => {
    expect(colour('--acme-toolbar-gradient')).toContain('linear-gradient');
    expect(colour('--acme-page-gradient')).toContain('linear-gradient');
    expect(colour('--acme-page-gradient')).toContain(colour('--acme-page-start'));
    expect(colour('--acme-page-gradient')).toContain(colour('--acme-page-end'));
  });
});
