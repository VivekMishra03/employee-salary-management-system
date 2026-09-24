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
