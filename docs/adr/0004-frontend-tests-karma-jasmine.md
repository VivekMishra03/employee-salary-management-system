# ADR-0004 — Frontend unit tests run on Jasmine + Karma in a real browser

- **Status:** Accepted
- **Date:** 2026-09-20
- **Supersedes:** [ADR-0003](0003-frontend-test-runner-vitest.md) (rejected)
- **Decided by:** Manas Mishra (human)
- **Amended in part by:** [ADR-0017](0017-frontend-tests-behaviour-not-appearance.md) (2026-09-26): the tests run in a real
  browser as decided here, but they check behaviour and wiring, not layout, CSS or colour.

## Context

`requirements.md` §9 specifies the frontend test layer as **Jasmine/Karma**.

Angular 21 no longer scaffolds Karma. `ng new` generates the `@angular/build:unit-test` builder
running **Vitest + jsdom**. ADR-0003 proposed following that default and amending the spec.

That proposal was **rejected**. The decision is to keep Jasmine + Karma as specified, and configure
it deliberately.

This is not a case of the framework removing a capability. `@angular-devkit/build-angular@21.2.24`
still ships the `:karma` builder and declares `karma: ^6.3.0` as an **optional peer dependency** —
verified against the npm registry before any change was made. Karma is supported in Angular 21; it is
simply no longer the default.

## Decision

Frontend unit tests run on **Jasmine 7 + Karma 6.4 driving a real Chrome**, via the
`@angular-devkit/build-angular:karma` builder.

Configuration:

| Item | Value |
|---|---|
| Builder | `@angular-devkit/build-angular:karma` (`frontend/angular.json`) |
| Config | `frontend/karma.conf.js` |
| Types | `jasmine` (`frontend/tsconfig.spec.json`) |
| Local browser | `Chrome` |
| CI browser | `ChromeHeadlessCI` — headless with `--no-sandbox --disable-gpu --disable-dev-shm-usage` |
| Removed | `vitest`, `jsdom` |

### Zoneless, and why that matters here

Angular 21 scaffolds a **zoneless** application: `frontend/src/app/app.config.ts` has no
`provideZoneChangeDetection`, and `zone.js` is not a dependency at all.

The reflex when wiring up Karma is to set `polyfills: ["zone.js", "zone.js/testing"]`, because that
is what every pre-Angular-18 tutorial shows. That would have added a dependency the application does
not use and quietly reintroduced zone-based change detection **in tests only** — tests would then
exercise a different change-detection model than production. The test runner must not dictate the
application's architecture.

Instead `polyfills` is empty and `TestBed` is given `provideZonelessChangeDetection()` explicitly.
Angular raises `NG0908` if this is missing, so the failure is loud rather than silent.

## Consequences

**Positive**
- The spec is honoured as written; no amendment to §9 was needed.
- Tests run in a **real browser**, not a DOM simulation. Real layout, real CSS, real focus and
  scroll behaviour. For a product whose core screens are dense data grids (FR-2.2) and charts
  (FR-4.3), this is meaningfully closer to what the HR Manager actually uses.
- Jasmine and Karma are long-established with deep documentation — a pragmatic advantage when the
  alternative is a runner Angular only recently adopted.
- The CSV-download path in FR-5.5, flagged in ADR-0003 as the likeliest thing jsdom could not
  faithfully cover, is now testable in the normal suite rather than needing a separate end-to-end
  layer.

**Negative — accepted deliberately**
- **A browser must be available.** Chrome is present locally; CI installs one explicitly via
  `browser-actions/setup-chrome`. This is a hard dependency that jsdom would not have imposed, and
  it is the main reason Angular moved away from Karma.
- **Browser launch is a fixed per-run cost** that jsdom would not have. Worth stating precisely,
  because the intuition here turned out to be wrong: the replaced Vitest/jsdom setup reported
  `Duration 16.51s` for 2 tests, of which **15.13s was jsdom environment startup**; Karma reports
  `Executed 1 of 1 SUCCESS (0.089 secs / 0.082 secs)`. On this project Karma is currently the
  *faster* of the two. Neither figure includes the Angular compile step, which dominates both.
  The honest statement is that browser startup is a real cost that scales with nothing, not that
  Karma is slow.
- **Off the framework default.** Angular's investment is going into the Vitest builder, so this
  configuration will need maintenance attention at future major upgrades. The `:karma` builder is
  supported today; if it is ever deprecated, revisit this ADR rather than patching around it.
- `karma.conf.js` is hand-maintained configuration that `ng new` would otherwise have generated.

**Determinism note (NFR-3):** `client.jasmine.random` is `true`, so tests run in a random order on
every run. NFR-3 requires order-independence, and randomised ordering surfaces a violation
immediately rather than months later on a CI machine.

## Alternatives rejected

- **Vitest + jsdom** (ADR-0003) — the Angular 21 default and lower friction, but jsdom is a DOM
  simulation and the spec asked for Karma. Rejected by the human.
- **Karma with zone.js polyfills** — the conventional wiring, rejected because it would make tests
  run under a change-detection model the application does not use.
