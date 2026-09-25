import { ComponentFixture } from '@angular/core/testing';

/**
 * Real-layout helpers for specs. Karma runs a real browser (ADR-0004), so a spec can measure what the
 * user would see instead of mocking it. TestBed attaches the fixture root to the document and removes
 * it after each test, so setting an explicit width here needs no separate clean-up.
 */
export function setHostWidth(fixture: ComponentFixture<unknown>, px: number): HTMLElement {
  const host = fixture.nativeElement as HTMLElement;
  host.style.display = 'block';
  host.style.width = `${px}px`;
  return host;
}

/** The right edge of the text itself, which is what a reader sees even when the element box clips or overflows it. */
export function textRightEdge(element: Element): number {
  const range = document.createRange();
  range.selectNodeContents(element);
  return range.getBoundingClientRect().right;
}

/** Types into a native input the way a user would, so Angular forms see an `input` event. */
export function typeInto(input: HTMLInputElement, text: string): void {
  input.value = text;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

export interface OverflowOptions {
  /** Selector for deliberate scroll containers: their own box must fit, but what they scroll is not judged. */
  ignore?: string;
}

/** True when `el` is not painted: display:none, visibility:hidden, inside a zero-size ancestor, or inside a collapsed (zero width or height) ancestor that clips. */
function isNotRendered(el: Element, host: HTMLElement): boolean {
  for (let node: Element | null = el; node && node !== host; node = node.parentElement) {
    const style = getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden') {
      return true;
    }
    const box = node.getBoundingClientRect();
    const collapsedAndClipping = (box.width === 0 || box.height === 0) && style.overflowX !== 'visible';
    if ((box.width === 0 && box.height === 0) || collapsedAndClipping) {
      return true;
    }
  }
  return false;
}

/** The nearest ancestor below the host that hides or scrolls its overflow and whose right edge `rect` passes. */
function clippingAncestorPassed(el: Element, host: HTMLElement, rect: DOMRect): Element | null {
  for (let ancestor = el.parentElement; ancestor && ancestor !== host; ancestor = ancestor.parentElement) {
    if (getComputedStyle(ancestor).overflowX !== 'visible' && rect.right > ancestor.getBoundingClientRect().right + 1) {
      return ancestor;
    }
  }
  return null;
}

/**
 * Describes everything that would make a page scroll sideways inside `host`: the host's own scrollWidth, any
 * descendant whose right edge passes the host's, and any descendant that an unlisted clipping ancestor cuts off
 * (that content is lost, not scrollable). Exempt: whatever sits inside a scroll container named by `options.ignore`
 * (that box is still judged itself), visually hidden text such as a chart's accessible table, and elements that are
 * not rendered at all, such as the parked body of an inactive Material tab.
 */
export function horizontalOverflowOffenders(host: HTMLElement, options: OverflowOptions = {}): string[] {
  const offenders: string[] = [];
  const describe = (el: Element): string => `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : ''}`;
  if (host.scrollWidth > host.clientWidth + 1) {
    offenders.push(`host scrollWidth ${host.scrollWidth} > clientWidth ${host.clientWidth}`);
  }
  const hostRight = host.getBoundingClientRect().right;
  for (const el of Array.from(host.querySelectorAll('*'))) {
    // Material's .mat-focus-indicator is a decorative focus ring the chip label clips by design; it carries no content.
    if (el.closest('.visually-hidden, .cdk-visually-hidden') || el.classList.contains('mat-focus-indicator')) {
      continue;
    }
    const scroller = options.ignore ? el.parentElement?.closest(options.ignore) : null;
    if (scroller && host.contains(scroller)) {
      continue;
    }
    if (isNotRendered(el, host)) {
      continue;
    }
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0) {
      continue;
    }
    if (rect.right > hostRight + 1) {
      offenders.push(`${describe(el)} right ${Math.round(rect.right)} > host right ${Math.round(hostRight)}`);
      continue;
    }
    const clipper = clippingAncestorPassed(el, host, rect);
    if (clipper) {
      offenders.push(`${describe(el)} right ${Math.round(rect.right)} is cut off by ${describe(clipper)}`);
    }
  }
  return offenders;
}

/** The assertion form of {@link horizontalOverflowOffenders}: the failure message names each offending element. */
export function expectNoHorizontalOverflow(host: HTMLElement, options: OverflowOptions = {}): void {
  expect(horizontalOverflowOffenders(host, options)).withContext('horizontal overflow').toEqual([]);
}

/**
 * A box that scrolls sideways must be reachable without a mouse (WCAG 2.1.1): focusable, named as a region, and
 * visibly outlined when focused from the keyboard.
 */
export function expectKeyboardScrollRegion(box: HTMLElement): void {
  expect(box.getAttribute('tabindex')).withContext('tabindex').toBe('0');
  expect(box.getAttribute('role')).withContext('role').toBe('region');
  expect((box.getAttribute('aria-label') ?? '').trim()).withContext('aria-label').not.toBe('');
  box.focus({ focusVisible: true } as FocusOptions);
  expect(document.activeElement).withContext('focus lands on the box').toBe(box);
  expect(getComputedStyle(box).outlineStyle).withContext('focus outline style').not.toBe('none');
  expect(parseFloat(getComputedStyle(box).outlineWidth)).withContext('focus outline width').toBeGreaterThan(0);
  expectFocusRingNotClipped(box);
}

/** An ancestor that hides or scrolls overflow cuts off an outline drawn outside the box, so the ring must fit inside it. */
export function expectFocusRingNotClipped(box: HTMLElement): void {
  const style = getComputedStyle(box);
  const reach = Math.max(0, parseFloat(style.outlineWidth) + parseFloat(style.outlineOffset));
  const own = box.getBoundingClientRect();
  const ring = { left: own.left - reach, right: own.right + reach, top: own.top - reach, bottom: own.bottom + reach };
  // body is the viewport's scroller, not a clip box: a spec host sits flush against it.
  for (let ancestor = box.parentElement; ancestor && ancestor !== document.body; ancestor = ancestor.parentElement) {
    const overflow = getComputedStyle(ancestor);
    if (overflow.overflowX === 'visible' && overflow.overflowY === 'visible') {
      continue;
    }
    const clip = ancestor.getBoundingClientRect();
    const describe = `${ancestor.tagName.toLowerCase()}.${ancestor.className}`;
    if (overflow.overflowX !== 'visible') {
      expect(ring.left).withContext(`focus ring left edge vs ${describe}`).toBeGreaterThanOrEqual(clip.left - 0.5);
      expect(ring.right).withContext(`focus ring right edge vs ${describe}`).toBeLessThanOrEqual(clip.right + 0.5);
    }
    if (overflow.overflowY !== 'visible') {
      expect(ring.top).withContext(`focus ring top edge vs ${describe}`).toBeGreaterThanOrEqual(clip.top - 0.5);
      expect(ring.bottom).withContext(`focus ring bottom edge vs ${describe}`).toBeLessThanOrEqual(clip.bottom + 0.5);
    }
    return;
  }
}

