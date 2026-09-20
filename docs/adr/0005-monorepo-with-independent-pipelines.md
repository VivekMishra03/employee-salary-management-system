# ADR-0005 — One repository, two independent pipelines

- **Status:** Accepted
- **Date:** 2026-09-20
- **Deciders:** Manas Mishra (human), Claude Opus 5 (pair)

## Context

`backend/` (Spring Boot) and `frontend/` (Angular) live in one repository and deploy to two different
platforms — Render and Vercel. A reasonable objection was raised: *if they deploy separately, should
they not be separate repositories?*

The objection couples two things that are not coupled. **Deployment independence is a CI/CD concern,
not a source-control one.** Both Render and Vercel build from a subdirectory of a repository, so two
independent deployments from one repo is a standard, supported configuration — not a workaround.

The repository decision therefore has to be made on other grounds.

## Decision

**One repository**, with `backend/` and `frontend/` as sibling directories, and **two separate
GitHub Actions workflows** with path filters so each stack builds only when its own code changes.

- `.github/workflows/ci-backend.yml` — triggers on `backend/**`
- `.github/workflows/ci-frontend.yml` — triggers on `frontend/**`

Each workflow also watches its own file, so a change to how a stack is built re-verifies that stack.

**Expected routing** — derived from the path filters as written, and independently re-derived during
the review gate. Not yet observed on a real push, because these workflows have never executed:

| Changed path | Workflows triggered |
|---|---|
| `backend/src/.../EmployeeService.java` | CI Backend |
| `backend/build.gradle` | CI Backend |
| `frontend/src/app/app.ts` | CI Frontend |
| `frontend/package.json` | CI Frontend |
| `requirements.md`, `docs/**` | none |

The confirming observation is the first backend-only and frontend-only push after this lands, each
showing exactly one workflow start. Until then this table is a prediction, not a result
(`CLAUDE.md` §4.5, §4.8).

Checked for the classic path-filter blind spot — a build input living outside the filtered
directories, which would let a stack change without triggering its pipeline. There is none: every
Gradle file is inside `backend/`, and `package.json`, `angular.json`, `karma.conf.js` and
`tsconfig*.json` are all inside `frontend/`. The repository root holds no build inputs.

## Rationale

**Why one repository**

1. **One developer, one release cadence.** Polyrepo exists to decouple *teams* and *release
   schedules*. Neither is a factor here; the split would buy isolation nobody needs.
2. **Cross-stack changes are the norm, not the exception.** Nearly every requirement touches both
   sides — FR-4's analytics endpoints and the dashboard that renders them ship together. In one repo
   that is a single atomic commit. Split, it is two pull requests in two repositories with a
   contract-versioning dance between them, and a window where `main` in each is mutually
   incompatible.
3. **Shared governing artifacts.** `requirements.md`, `CLAUDE.md`, `docs/WORKFLOW.md` and the ADRs
   govern both stacks and belong to neither. In a split they would have to be duplicated, or exiled
   to a third repository — which is worse than either option.
4. **The brief asks for one.** It says *"Commit your code to a Git repository"* and *"Share the
   repository link"* — singular, twice — and grades commit history for how the solution evolved.
   Split across two repositories, a reviewer follows one link and sees half the work, and the
   evolution story is fragmented across two timelines that cannot be read together.

**Why separate workflows rather than one with two jobs**

The original single `ci.yml` ran both jobs on every push, so editing a README launched Chrome and ran
Gradle. That is the coupling genuinely worth removing, and the fix is in CI, not in source control.
Path filters are per-workflow in GitHub Actions, not per-job, so two files is the mechanism — a
single workflow cannot express this without an extra change-detection action.

## Consequences

**Positive**
- Each stack builds, fails and reports independently. A broken Angular test does not block a backend
  push, and vice versa.
- Docs-only changes run no CI at all, which is correct — nothing executable changed.
- Atomic cross-stack commits, one history, one issue tracker are all retained.

**Negative / watch out for**
- **Required status checks become a trap.** GitHub names a check `<workflow name> / <job name>`, so
  the exact strings that appear in branch protection are:
  - `CI Backend / Backend (Java 17)`
  - `CI Frontend / Frontend (Angular 21)`

  If branch protection ever marks *both* as required, a frontend-only pull request will hang forever
  waiting on a backend check that never runs — GitHub reports a workflow skipped by a path filter as
  *pending*, not *success*. If branch protection is introduced, either mark neither as required, or
  add an always-running aggregator job that reports success when its stack was untouched. Noted here
  with the literal check names because the point of this paragraph is to be findable by someone
  configuring branch protection months from now.
- Two workflow files now share several steps. Duplication is acceptable at this size; if it grows,
  extract a composite action rather than merging them back.
- Render and Vercel still need their own build filters so neither redeploys when only the other's
  directory changed. That is deployment configuration, handled in M9.

## When this decision should be revisited

Split the repository if any of these become true: the backend becomes a versioned product serving
multiple independent clients; separate teams own the two stacks with different release cadences;
compliance requires different access boundaries; or repository size makes working on one stack
painful because of the other.

None of these apply at 10,000 employees, one HR Manager and one developer.
