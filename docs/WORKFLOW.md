# Development workflow — the feedback loop

This document describes how work actually moves through this repo. It exists because the main risk in
an AI-assisted build is not slow progress; it is **fast progress in the wrong direction**, where an
early wrong assumption is restated until it reads as fact and gets built on for hours.

The loop below is designed to make wrong turns surface within one task rather than at the end.

---

## The loop

```
        ┌──────────────────────────────────────────────────────────────┐
        │                                                              │
        ▼                                                              │
┌───────────────┐                                                      │
│  0. BRIEF     │  Human + Claude agree the task: which requirement     │
│               │  IDs, what "done" means, what is out of scope         │
└───────┬───────┘                                                      │
        ▼                                                              │
┌───────────────┐                                                      │
│ 1. IMPLEMENT  │  RED → GREEN → REFACTOR                              │
│    (TDD)      │  Failing test output captured as evidence            │
└───────┬───────┘                                                      │
        ▼                                                              │
┌───────────────┐                                                      │
│ SPEC          │  Fresh subagent. Reads requirements.md + the diff.    │
│ COMPLIANCE    │  "Is this what we specified — no less, no more?"     │
│    (always)   │                                                      │
└───────┬───────┘                                                      │
        ▼                                                              │
┌───────────────┐                                                      │
│ CODE          │  Fresh subagent. Runs the tests itself.              │
│ CORRECTNESS   │  "Is this correct, tested, safe?"                    │
│  (only if     │  Skipped when only deps/config/docs changed.         │
│ source code   │                                                      │
│   changed)    │                                                      │
└───────┬───────┘                                                      │
        ▼                                                              │
┌───────────────┐   changes requested (max 2 rounds)                   │
│ 4. HUMAN      │ ─────────────────────────────────────────────────────┘
│    REVIEW     │
└───────┬───────┘  approved
        ▼
┌───────────────┐
│ 5. COMMIT     │  Conventional commit, requirement IDs in the body
└───────────────┘
```

---

## Stage 0 — Brief

Before code, the human and Claude agree in writing:

- **Requirement IDs** in scope for this task
- **Acceptance criteria** — what observable behaviour proves it works
- **Explicitly out of scope** for this task, so "while I was in there" has no room to grow
- **Unknowns** — anything to verify rather than assume (does Neon enable `btree_gist`? does this
  Angular Material version ship the component we want?)

A task whose brief cannot be written in five lines is too big. Split it.

## Stage 1 — Implement (TDD)

Per `CLAUDE.md` §2. The non-negotiable part is that **the RED output is captured**. A test that
passed the first time is evidence that something is wrong — either it tests nothing, or the feature
already existed and the task was misunderstood. Both are worth knowing before writing more code.

## Stage 2 — Spec compliance review

A **fresh subagent** (`.claude/agents/spec-compliance-review.md`) that did not write the code reads
`requirements.md` and the diff from disk.

Why a separate agent: the implementer has the intended design in context and will read the code as
matching it. A reviewer with no such context reads what is actually there. This is the primary
defence against context poisoning — it resets the frame on every task.

Catches: missing sub-requirements, untested acceptance criteria, invented features, out-of-scope work,
contradictions with the spec.

## Stage 3 — Code correctness review *(conditional)*

A second **fresh subagent** (`.claude/agents/code-correctness-review.md`). Runs the test suite itself
rather than trusting a claim, and looks for defects in correctness, money handling, test quality,
security, layering and performance.

Why separate from Stage 2: a reviewer asked to check both scope and quality does neither well. Splitting
them keeps each review specific, and specific reviews find real bugs.

### When it runs

**Only when the change touches source code** — in either stack. Dependency manifests and
configuration files do not trigger it, however large the change.

Code is `backend/src/**/*.java` and `frontend/src/**/*.{ts,html,scss}`. Everything else is build,
dependency or process material: `build.gradle`, `package.json`, `package-lock.json`, `angular.json`,
`tsconfig*.json`, `karma.conf.js`, `backend/src/main/resources/**`, CI YAML, `.gitignore` and all
Markdown.

Checked mechanically, not by impression:

```bash
CODE='^(backend/src/.*\.java|frontend/src/.*\.(ts|html|scss))$'
{ git status --porcelain -uall | sed 's/^...//'; git diff --name-only HEAD; } \
  | sort -u | grep -E "$CODE"
```

`-uall` is required: without it, git collapses a new untracked directory to `?? backend/` and the
grep silently misses every file inside it, skipping the gate on exactly the large changes that most
need it. This was observed in M0.

Any output means the gate is mandatory. No output means it is skipped, and the Stage 4 report must
record `Code correctness: SKIPPED (no source-code files changed)` so the skip is a visible decision.

The rationale is signal-to-noise: this gate's value is in correctness, money handling and test
quality, and those defects live in source code. Running it against a dependency bump or a YAML tweak
produces padding rather than findings, and a reviewer that habitually reports nothing is one the team
learns to ignore.

Stage 2 still runs on **every** change, including dependency and config edits — which is where
unrequested scope most often enters — so nothing goes unreviewed.

## Stage 4 — Human review

The human receives a short report — no code dumps:

```
## Task: <name>
**Requirements:** FR-3.1, FR-3.2
**Built:** <2–4 lines>
**Tests:** 12 new, 47 total — all passing (output below)
**Spec compliance:** PASS (1 gap found and fixed: back-dated case untested)
**Code correctness:** APPROVE WITH COMMENTS (1 blocker fixed: unscaled BigDecimal divide)
**Decisions needing your call:** <or "none">
**Proposed commit:** feat(salary): ...
```

Then one of: **approve** · **change this** · **that is the wrong approach**.

Fix loop is capped at **two rounds**. Past that, Claude stops and escalates with the disagreement
stated plainly rather than thrashing.

## Stage 5 — Commit

Conventional commit with requirement IDs in the body (`CLAUDE.md` §6). No amending, no squashing,
no force-push — the history is a graded artifact and must stay honest.

---

## Human checkpoints

Not every stage needs the human, but these do:

| Checkpoint | Why |
|---|---|
| **Spec approval** | Before any code. Wrong scope is the most expensive error available |
| **Data model sign-off** (M1) | Every later decision inherits from the schema; changing it at M6 is surgery |
| **Each milestone** | Direction check while correction is still cheap |
| **Any spec change** | Only the human may change `requirements.md`, always with an ADR |
| **Irreversible actions** | Deploys, schema drops, anything rewriting git history |
| **Unresolved disagreement** | After two failed fix rounds |

---

## Anti-hallucination practices

Beyond the rules in `CLAUDE.md` §4, these are structural:

**Verify, don't recall.** Before relying on a file, read it. Before claiming a build passes, run it
and paste the output. Before saying it is deployed, `curl` the URL.

**Write decisions down.** Architectural choices go in `docs/adr/` at the moment they are made. A
decision that lives only in chat is lost at the next context boundary and will be silently reinvented
differently — the classic way an AI-built codebase acquires two incompatible approaches to the same
problem.

**Keep the spec upstream of the code, always.** When code and spec disagree, the spec is right until
a human says otherwise. The opposite habit — quietly editing the spec to match the build — destroys
the only record of intent.

**Prefer executable evidence.** A passing test beats an assertion in prose. A query result beats a
recollection of a row count. Where a claim can be checked by running something, run it.

**Log the prompts.** Significant prompts and Claude's plans go in `docs/prompts/`. The brief asks for
this as an artifact, and it doubles as a record of where instructions were ambiguous.

---

## Artifacts this workflow produces

| Artifact | Where | Purpose |
|---|---|---|
| Requirements spec | `requirements.md` | Source of truth for scope |
| Operating rules | `CLAUDE.md` | How agents must work |
| This workflow | `docs/WORKFLOW.md` | How the feedback loop runs |
| Architecture decisions | `docs/adr/` | Why each significant choice was made |
| Design notes | `docs/design/` | Data model, API, sequence notes |
| Prompt log | `docs/prompts/` | Intentional-AI-use evidence |
| Review records | commit bodies + `docs/reviews/` | Evidence the gates ran |
| Commit history | git | The development process itself |
