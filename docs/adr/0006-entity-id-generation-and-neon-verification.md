# ADR-0006 — Entity ID generation strategy, and where `btree_gist` gets verified

- **Status:** Accepted
- **Date:** 2026-09-20
- **Deciders:** Manas Mishra (human), Claude Opus 5 (pair)
- **Raised by:** the M1.1 spec-compliance and code-correctness reviews

Two decisions, recorded together because both are M1.1 findings that must be settled **before** M1.2
writes the entities and would be expensive to reverse afterwards.

---

## Part A — Entities use `SEQUENCE` generation, not `IDENTITY`

### Context

`requirements.md` §6.2 specifies `id BIGSERIAL PK` for every table. In JPA that maps naturally to
`@GeneratedValue(strategy = IDENTITY)`, which is the reflex choice and what most tutorials show.

The code-correctness review flagged the consequence: **Hibernate disables JDBC insert batching
entirely when `IDENTITY` is used.** It has no choice — it must execute each insert immediately to
learn the generated key, so there is nothing to batch.

That collides with FR-6.1, which requires seeding **10,000 employees** plus roughly 35,000 salary
records, plus departments, locations, roles, pay bands and exchange rates. With `IDENTITY` that is
one network round trip per row. Against Neon — a hosted database reached over the network, not a
local socket — tens of thousands of round trips is the difference between a seed that takes seconds
and one that takes many minutes.

The finding arrived as a warning about a `spring.jpa.properties.hibernate.jdbc.batch_size` setting
that had been added prematurely (and has since been removed, being M5 work). But the setting was
never the point: **the ID strategy is a schema-level decision that M1.2 is about to bake in.**

### Decision

Entities use `@GeneratedValue(strategy = GenerationType.SEQUENCE)` with an explicit
`@SequenceGenerator` and a pooled `allocationSize` (50), backed by a real sequence per table.

`BIGSERIAL` in `requirements.md` §6.2 stays as written — it already creates a sequence underneath, so
this is a decision about how JPA *uses* the column, not a change to the specified schema.

When the FR-6.1 seed is implemented at M5, batching is reinstated deliberately, together with
`reWriteBatchedInserts=true` on the JDBC URL (which is what actually collapses a batch into a single
multi-row insert on PostgreSQL) and a measured before/after figure. The number `100` was previously
in the config with nobody having benchmarked it; whatever replaces it will have been measured.

### Consequences

**Positive**
- FR-6.1 seeding can batch, which is the only reason batching is in scope at all.
- Sequences are allocated in blocks, so inserts do not serialise on a single round trip each.

**Negative, and accepted**
- IDs are no longer strictly gapless or strictly monotonic — a pooled allocator reserves a block and
  unused values are discarded on restart. This is harmless: `id` is a surrogate key. The
  human-facing identifier is `employee_code` (`requirements.md` §6.2), which is generated separately
  and is what HR actually reads.
- Slightly more mapping ceremony per entity than `IDENTITY`.

**Rejected:** `GenerationType.AUTO`. On Hibernate 6 with PostgreSQL it resolves to a sequence anyway,
but leaves the behaviour implicit and version-dependent. A decision this consequential should be
readable in the entity.

---

## Part B — `btree_gist` is verified on the test engine at M1, and on Neon at M9

### Context

`requirements.md` §11 records the risk as *"`btree_gist` unavailable on Neon"* with the mitigation
*"Verified at M1"* — without saying **where** verification happens.

That ambiguity caused a real error. M1.1 proved `btree_gist` present on the Zonky embedded binary
and the risk was reported to the human as closed. Both review gates independently rejected that: the
embedded binary ships the full contrib set and runs as a local superuser, which is no evidence about
a managed service with an extension allowlist and least-privilege roles.

The concrete failure that remains possible: `CREATE EXTENSION` in `V1__enable_extensions.sql` fails
on Neon with `permission denied to create extension "btree_gist"`, the first deploy dies inside
Flyway, and the documented fallback was never prepared because the risk looked closed — while the
test suite was green throughout.

### Decision

Split the verification explicitly into two checkpoints:

| Checkpoint | Where | Proves | Status |
|---|---|---|---|
| **M1** | Zonky embedded PostgreSQL 16.9 | The migration SQL is correct, and a gist `EXCLUDE` mixing `BIGINT` with `daterange` genuinely rejects overlapping intervals | **Done** — `SchemaMigrationTest` |
| **M9** | The real Neon instance | Neon permits both extensions for our role, and V1 applies against it | **Open** |

Until the M9 checkpoint passes, the §11 risk is **open**, and the documented fallback stays live:
service-level enforcement plus a unique partial index on `(employee_id) WHERE effective_to IS NULL`.

Test names and javadoc were narrowed to claim only what they prove — "V1 installs the extension on
the test engine", not "btree_gist is available".

### Why not provision Neon now

Provisioning early would close the risk sooner, which is genuinely tempting. It is deferred because
the schema is still being written across M1.2–M1.6, and a Neon instance created now would be
migrated repeatedly against a moving target with no test depending on it. The risk is cheap to carry
because the fallback is known and the blast radius is one migration file.

**The trigger to revisit:** if anything in M1.2–M1.6 adds a *second* dependency on a Postgres
extension or a superuser-only feature, provision Neon immediately rather than accumulating
unverified platform assumptions.

### Consequences

- The M9 deploy milestone gains an explicit first step: apply migrations to Neon and record the
  observation, **before** anything else in M9 is attempted.
- `requirements.md` §11 needs its mitigation wording amended to name the two environments. That is a
  specification change and belongs to the human (`CLAUDE.md` §5); proposed wording accompanies this
  ADR.
