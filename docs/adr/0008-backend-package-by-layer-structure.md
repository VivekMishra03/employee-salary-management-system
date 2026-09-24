# ADR-0008 — Backend package structure: by layer, not by feature

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** Manas Mishra (human) — directed via a structure specification added directly to
  `docs/adr/`; Claude Sonnet 5 executed the refactor

## Context

Through M1.2/M1.3 the backend was organised **by feature**: `com.acme.salary.department`,
`.location`, `.jobrole`, `.employee`, each package holding that feature's entity, repository and
tests together. This was a deliberate choice at the time, not an oversight — package-by-feature
keeps everything touching one concept in one place and is a common, well-regarded convention as a
codebase grows.

The human specified a different, also common and well-regarded convention: **package by layer**
(`config`, `controller`, `dto`, `exception`, `model`, `repository`, `service`), matching the
structure most Spring Boot tutorials and many real-world teams use, and the shape a reviewer of
this assessment is likely to expect. There is no objectively "correct" answer between the two —
this is a judgment call the human made directly, and it is now the project's standard.

## Decision

Reorganise `backend/src/{main,test}/java/com/acme/salary/` by layer:

```
com.acme.salary/
├── SalaryManagementApplication.java
├── config/       -- @Configuration classes (ClockConfig, JpaAuditingConfig)
├── model/        -- @Entity classes and their enums (Department, Location, JobRole, Employee,
│                    Gender, EmploymentStatus, EmploymentType)
├── repository/   -- Spring Data interfaces (one per entity)
├── controller/   -- not yet created; arrives with the first @RestController (M3)
├── dto/          -- not yet created; arrives with the first request/response type (M3)
├── exception/    -- not yet created; arrives with the first custom exception + @RestControllerAdvice
└── service/      -- not yet created; arrives with the first @Service (M3)
```

`controller/`, `dto/`, `exception/` and `service/` are not created empty now — Java has no notion of
an empty package worth committing, and CLAUDE.md's anti-scope-creep rule argues against
speculative structure for code that doesn't exist yet. Each appears the moment the milestone that
needs it lands.

**What moved:**

| Old (by feature) | New (by layer) |
|---|---|
| `department.Department`, `location.Location`, `jobrole.JobRole`, `employee.{Employee,Gender,EmploymentStatus,EmploymentType}` | `model.*` |
| `department.DepartmentRepository`, `location.LocationRepository`, `jobrole.JobRoleRepository`, `employee.EmployeeRepository` | `repository.*` |
| `config.*` | unchanged — already matched |
| Corresponding test classes | mirror the same move |
| `persistence.{SchemaMigrationTest,DatasourceConfigurationTest}` | `config.*` — both verify configuration-level behaviour (Flyway, `ddl-auto`, datasource startup), not a specific entity or repository, so they sit with `config/` rather than under a `persistence` package the layer convention doesn't define |

**Migration SQL is untouched.** `src/main/resources/db/migration/*.sql` already follows the
standard Flyway convention, which coexists with either Java package convention; nothing in this ADR
changes it.

## What the refactor actually required

A rename is not purely mechanical in Java: **same-package references need no import, cross-package
references do.** Four repositories (`DepartmentRepository`, `EmployeeRepository`,
`JobRoleRepository`, `LocationRepository`) previously shared a package with their entity and
referenced it with no import statement at all; moving the entity to `model/` and the repository to
`repository/` turned that into a missing-symbol compile error until an explicit
`import com.acme.salary.model.X;` was added. The same applied to four repository test classes
referencing their own entity or its enums. This was caught immediately by running the full suite
after the move, exactly as CLAUDE.md's REFACTOR step requires ("re-run the whole suite; behaviour
must not change") — it is recorded here because it is the one part of "a rename" that is not just
moving files.

## Consequences

**Positive**
- Matches a structure the human specified directly and a reviewer is likely to expect.
- `model/` and `repository/` scale predictably: the next entity is another file in an existing
  package, not a new package.

**Negative, accepted**
- Package-by-layer means an entity and its repository (or, once they exist, its controller and
  service) sit in different directories, so understanding one feature end-to-end means reading
  across `model/`, `repository/`, `service/` and `controller/` rather than one directory. This is
  the standard tradeoff against package-by-feature, made explicitly rather than by default.
- `model/` will eventually hold every entity in the schema (`requirements.md` section 6 lists ten),
  as a single flat package. If that becomes hard to navigate, revisit with an ADR rather than
  silently sub-packaging.

## Verification

`cd backend && ./gradlew cleanTest test` — full suite green, 37 of 37, before and after the move
compared for behaviour (not literal count: M1.3's fix round added tests between the two runs). No
test was changed to make this pass; the failures encountered were missing imports, fixed as
described above, not test or production logic changes.
