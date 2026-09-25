# ADR-0016 — Responsive layout: container queries, one handset breakpoint, scrolling tables

- **Status:** Accepted — items marked ⚠ are judgment calls the human should confirm or overrule
- **Date:** 2026-09-25
- **Basis:** requirements.md section 4, out-of-scope table: "Mobile-native apps ... Desktop-first responsive web
  matches how compensation work is actually done: a large screen and dense tables." There is no FR or NFR
  for responsiveness, so this work traces to that row and to the screens' own requirements (FR-1.1, FR-2.1,
  FR-2.2, FR-2.5, FR-3.2, FR-4). requirements.md is not edited.

## Context
The human reported that the screens did not work on a phone. The shell had a fixed sidebar, the directory
had a ten-column table, and dialogs and filters had fixed widths.

## Decisions
- **Desktop stays the primary case.** Nothing was removed or reordered on wide screens.
- **Component layouts use CSS container queries**, not viewport media queries, so a real-Chrome test can put a
  component in a 360 px box and measure it. Media queries cannot be exercised that way, because the test
  browser's viewport is fixed. The dialog panel width is the exception (dialogs render in the overlay, outside
  any component), and uses one `@media (max-width: 599px)` rule in `styles.scss`.
- **One handset breakpoint for the shell, 768 px**, through the CDK `BreakpointObserver`, behind a small
  `HandsetService` that tests can replace. On a handset the sidebar starts closed and overlays the page with a
  backdrop, and choosing a link closes it. On wider screens it is a side panel, open by default. The user's own
  open or close choice is kept until the screen crosses 768 px.
- **Tables scroll inside their own box** (`.table-scroll`) instead of being reshaped, so no column or figure is
  removed on a phone and the page itself never scrolls sideways. Each such box is keyboard-focusable, named,
  and shows a focus outline, so a keyboard or screen-reader user can reach the hidden columns.
- **Nothing is hidden on phones.** An early version hid the paginator's page-size picker; that removed a
  function and was reversed. The picker wraps instead.
- **Panel layouts switch at 480 px, page layouts at 640 px, the dialog form at 520 px.** The dashboard's
  half-width panels are about 600 px wide on a 1280 px screen, so a 640 px switch there would have degraded the
  desktop.
- **Touch targets** are at least 44 px on phones; table and note text is 14 px there. Chart labels are drawn
  smaller (about 10 px) because SVG text scales with the chart. ⚠ That is below the 14 px body-text aim.

## Browser baseline ⚠
Container queries need Chrome 105, Safari 16 or Firefox 110 or newer. The shell height uses `100dvh`, which
needs Chrome 108 or Safari 15.4, and falls back to `100vh`. In an older browser the page shows the desktop
layout. Angular 21 already drops the oldest browsers, so this is not a new restriction in practice.

## Testing
- `expectNoHorizontalOverflow` measures every element against its screen's host box at 360, 768 and 1280 px.
  Only descendants of a listed scroll box and non-rendered or visually hidden content are exempt. An earlier
  version exempted anything an ancestor clipped, so removing a scroll box inside a Material tab still passed;
  a mutation of exactly that now fails.
- Karma's headless window is fixed at 1280 x 900 (`ChromeHeadlessCI`), because Chrome's default of about 800 px
  sits just above the breakpoint and made the shell choose its phone layout in tests. This also applies in CI.

## Not verified
- Any real phone, iOS Safari behaviour such as the address bar and momentum scrolling, and the real Roboto
  font's metrics. The specs use whichever font the test Chrome resolves.
- The dialog overlay's width rule has no spec of its own; the forms are tested at the widths of their hosts.
- The layout has not been viewed by a person on a device. That review is the human's.
