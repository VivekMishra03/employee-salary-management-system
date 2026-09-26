# ADR-0017 — Frontend unit tests check behaviour and wiring, not appearance

- **Status:** Accepted — decided by the human owner on 2026-09-26. Amends the rationale of ADR-0004 and the
  "component rendering" wording of requirements.md section 9. requirements.md and CLAUDE.md are not edited by
  this ADR; the proposed CLAUDE.md wording is at the end and needs the human's approval.
- **Date:** 2026-09-26

## Context
The Karma suite had grown to 464 tests. A keyword classification of its 441 test blocks (approximate, about
±10%) found:

| What the test checks | Blocks | Share |
|---|---:|---:|
| Pure logic in `.ts` (services, utilities, guards, interceptor, validators, state) | 205 | 46% |
| Component behaviour observed through the template | 123 | 28% |
| Layout: widths, overflow, overlap | 66 | 15% |
| Colour and style: computed CSS, gradients, contrast | 47 | 11% |

The layout and colour tests came from ADR-0004's reasoning (a real browser gives "real layout, real CSS and
focus behaviour") and from the responsive work in ADR-0016. In practice they failed only when someone changed
appearance, never when behaviour or a requirement broke, and they carried most of the upkeep: a helper library
with tests of its own, a fixed browser window size, dependence on font metrics, and a focus test that was
flaky until its cause was found (recorded in the commit history of the shell tests).

## Decision
**A unit test must fail only when behaviour or a requirement breaks.** It must not fail because a colour,
spacing, size, gradient, icon, or the wording of a fixed label changed.

- **Kept:** logic in `.ts` files, and component tests that use the template as the way to drive and observe real
  behaviour: a click sends a request, a form blocks invalid input, an error is shown, a stale response is
  ignored, a suppressed group shows no figure (FR-4.5), the sidebar's open state follows Escape.
- **Removed (124 tests, 464 to 340):** layout and overflow measurements, colour, gradient and contrast checks,
  computed-CSS assertions, focus-outline visibility, and static markup checks such as a label, placeholder or
  icon name. Nine whole spec files and the helpers behind them were deleted: the layout and palette helpers,
  and the contrast function that existed only to test the palette.
- **Not removed, only listed:** 17 tests that are mostly static text or attribute checks (fixed notes, column
  header lists, link labels). They are listed at the end of this ADR for a second pass, and each was left alone
  because it is arguably a requirement's wording (for example that amounts are in USD).

Appearance is checked by looking at it. If a regression net for layout is wanted later, the right tool is a
small screenshot suite in a browser-automation tool, kept apart from unit tests.

## Consequences
- **Lost safety net:** nothing now stops a table overflowing at phone width, a search label overlapping its
  icon, or a palette change dropping text below WCAG AA contrast. The contrast figures in the styles are worked
  out by hand and were last measured on the earlier palette. A token-level contrast script in CI would be the
  cheap replacement if this matters; it is not built.
- **What still holds:** the sidebar's open, closed and phone-mode logic, keyboard and Escape behaviour, the
  requests and state of every screen, and every requirement ID still has tests (checked by search after the
  removal).
- **Kept on purpose:** Karma stays in a real Chrome (ADR-0004), fixed at 1280 x 900 in the CI launcher, because
  the shell's phone/desktop logic reads the viewport. The CI time-zone matrix stays: it protects date logic.
- ADR-0016's "Testing" section describes tests that no longer exist; see its addendum.

## Proposed wording for CLAUDE.md section 2 (for the human to approve)
Add under "Test rules":

> - **Test behaviour and wiring, never appearance.** A unit test fails only when behaviour or a requirement
>   breaks. Do not assert colours, gradients, spacing, sizes, geometry, computed CSS or icons. Do not assert the
>   wording of a static label **unless that text is itself a requirement** (for example the suppression notice
>   for a small group, or the exchange-rate date). A component test is fine when the template is the way to drive
>   or observe behaviour (a click sends a request, invalid input is refused, an error appears). Look-and-feel
>   changes are checked by looking at them and are not unit-tested.

Open question for the human: whether accessibility attributes (`tabindex`, `role`, `aria-label`) count as wiring
that may be tested. The wording above leaves them out; see "Known losses" below.

## Second-pass candidates (17 tests that are mostly static text or attributes; kept for now)
Each is arguably a requirement's own wording, or an accessibility hook, so none was removed. The human decides.

1. `app.spec.ts`: the root component bootstraps and renders (asserts only that it exists).
2. `employee-list`: only columns the API can sort by offer a sort header (header lists; the whitelist is ADR-0010).
3. `analytics-filters`: the six filters are search, department, country, status, type and level (label list).
4. `analytics.component`: shows the title and every panel (title text and tag presence).
5. `analytics.component`: says amounts are converted at the rate effective when each salary was recorded (fixed sentence; FR-3.5, assumption 2).
6. `summary-panel`: says the figures are in USD (fixed text).
7. `gender-gap`: a note explains that a minimum group size applies (fixed note; FR-4.5).
8. `gender-gap`: a note explains what a positive gap means (fixed note; FR-4.5).
9. `pay-bands`: the adherence chip carries the attribute the stylesheet colours by (a styling hook).
10. `trend-panel`: says the trend counts everyone employed in each period (fixed sentence; ADR-0013).
11. `trend-panel`: the scope note is present while loading and when the server refuses the range (element presence).
12. `trend-panel`: a payroll figure is shown in USD (header text).
13. `shell.component`: navigation offers the employee directory and the analytics dashboard, and nothing else (link labels; FR-2.1, FR-4).
14. `shell.component`: a menu button controls the sidenav and starts expanded (aria-label string and link count).
15. `shell.component`: the menu icon stays visible and named while collapsed (size, visibility, icon name).
16. `shell.responsive`: a handset is a viewport of at most 767.98 px (a constant).
17. `shell.responsive`: the drawer starts closed and floats over the content (partly a rectangle check).

Three more mix a static list with real behaviour and should stay: the comparison panel's grouping options with its
request, the gender panel offering department and job level only, and the status options with the value sent.

## Known losses from the removal, for the human to accept or restore
- No test asserts that the paginator's page-size picker is rendered on phones (ADR-0016 says nothing is hidden).
- The attributes that make horizontally scrolling tables keyboard-reachable (`tabindex="0"`, `role="region"`, an
  `aria-label`) and the search field's `aria-label` are no longer tested. They are accessibility wiring, so this is
  a judgment call about whether "wiring" includes them.
