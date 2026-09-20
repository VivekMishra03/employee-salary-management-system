# ADR-0003 — Frontend unit tests run on Vitest + jsdom, not Karma

- **Status:** **REJECTED** (2026-09-20) — superseded by [ADR-0004](0004-frontend-tests-karma-jasmine.md)
- **Date:** 2026-09-20
- **Raised by:** the M0 requirements-analyst gate

> **Outcome:** rejected by the human reviewer. The proposal was to follow Angular 21's new default
> and amend `requirements.md` §9 accordingly. The decision was to **keep Jasmine + Karma** as the
> specification originally required, and to configure it explicitly rather than adopt the framework
> default. The reasoning is in ADR-0004.
>
> This record is retained rather than deleted. A rejected option is part of the decision history: it
> documents that the Vitest route was considered with evidence, and why it was not taken. The
> analysis below stands as written at the time.

## Context

`requirements.md` §9 specifies the frontend test layer as **"Jasmine/Karma"**. That was written
during planning, from the long-standing Angular default.

It is now stale. Angular 21's `ng new` no longer scaffolds Karma. The generated project uses the
`@angular/build:unit-test` builder (`frontend/angular.json:72-74`) with **Vitest 4 + jsdom**
(`frontend/package.json:27,30`). This was observed, not assumed — `npm test -- --watch=false`
reports `RUN v4.1.11` and passes.

This is a discovered fact about the framework, not a convenience choice someone made. Angular 21
does not offer Karma without deliberately reinstalling and reconfiguring it.

## Decision

Use the Angular 21 default: **Vitest 4 with the jsdom environment**, and amend `requirements.md` §9
to say so.

Explicitly **not** proposed: reinstating Karma. Doing so would mean fighting the framework's default
build pipeline to regain a runner the Angular team has moved away from, for no benefit to any
requirement in the spec.

## Consequences

**The substance of §9 is unaffected.** The frontend row of the test-strategy table specifies
*"component rendering, filter state, interceptor attaches token, guard redirects"*. All of that
remains achievable exactly as written. Test code is also largely unchanged in shape: Vitest provides
`describe` / `it` / `expect`, and `TestBed` is unchanged, so the existing spec
(`frontend/src/app/app.spec.ts`) needed no rewrite for the runner.

**Positive**
- Faster than a browser-based runner, and it satisfies NFR-3's "fast, deterministic" requirement.
- No browser binary is needed in CI. This matters here: Docker is unavailable locally, and a
  headless-Chrome step in `.github/workflows/ci.yml` would be one more thing that can rot.

**Negative — the one real trade**
- jsdom is a DOM *simulation*, not a browser. Anything genuinely browser-dependent — real layout and
  measurement, actual CSS resolution, focus and scroll behaviour, clipboard and file-download
  handling — is not faithfully covered. Nothing currently in `requirements.md` §FR-2 through FR-5
  requires it. The likeliest future candidate is CSV **download** behaviour in FR-5.5; if that needs
  real-browser verification it should be covered by a deliberate end-to-end test, not by pretending
  jsdom is a browser.

**Measurement note:** the jsdom environment costs ~15 s of setup per run (observed: 16.5 s total for
2 tests, of which 15.1 s was environment startup). Startup is paid once per run, not per test, so
this does not scale with suite size — but it sets a floor on frontend CI time.

## If approved

Amend `requirements.md`:
- §9, frontend row: "Jasmine/Karma" → "Vitest + jsdom"
- §8 stack table: Spring Boot 3.3 → 3.5.3, Angular 20 → Angular 21.2 (per ADR-0002)

This is the sanctioned path in `CLAUDE.md` §5 — a spec change made deliberately by the human with an
ADR recording why — and not the forbidden path of retrofitting the spec to whatever happened to get
built. The distinction matters: these corrections are driven by verified external facts (registry
versions, the installed Node engine constraint, the framework's generated output), all checked
*before* the build files were written, not by convenience after the fact.
