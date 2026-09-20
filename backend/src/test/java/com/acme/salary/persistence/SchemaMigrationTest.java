package com.acme.salary.persistence;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1: the schema is owned by Flyway and applied to a real PostgreSQL instance (NFR-5).
 *
 * <p>Tests run against an embedded PostgreSQL binary rather than H2, because the invariants this
 * system depends on -- the non-overlapping salary interval constraint (FR-3.2) and {@code
 * percentile_cont} for medians (FR-4.1) -- are features H2 either fakes or lacks. A repository test
 * that passes on H2 and fails in production is worse than no repository test (ADR-0001).
 *
 * <p><strong>Scope of what these tests prove.</strong> They establish that our migration SQL is
 * correct and that the capabilities we depend on exist <em>on the test engine</em>. They say nothing
 * about Neon, whose managed-extension allowlist and least-privilege roles are a separate question.
 * The risk at {@code requirements.md} section 11 is worded "btree_gist unavailable on Neon" and is
 * discharged only by running V1 against a real Neon instance -- see ADR-0006.
 */
@SpringBootTest
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("ADR-0007: tests run on the newest PostgreSQL Zonky and Flyway currently support")
    void database_isPostgres17() {
        // ADR-0001 chose embedded PostgreSQL over H2 on the grounds that tests must run against the
        // same engine as production. Live verification (ADR-0007) found Neon actually provisions
        // PostgreSQL 18, not 16 as originally assumed; Zonky has no 18.x Windows binary yet, and
        // 17.5.0 is also the newest version Flyway 11.7.2 declares official support for. 17.5.0 is
        // therefore the closest match currently achievable, not an exact one -- the version pin in
        // build.gradle is what makes that true, and this test is what stops it silently regressing
        // to whatever Zonky's library default happens to be.
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);

        assertThat(version).as("engine reported by the embedded instance").startsWith("17.");
    }

    @Test
    @DisplayName("NFR-5: Flyway applied every migration in order, and none failed")
    void flyway_appliedAllMigrationsSuccessfully() {
        List<String> succeeded = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL "
                        + "ORDER BY installed_rank",
                String.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

        // Asserting the specific versions in order, not merely count(*) > 0, which would pass on
        // any non-empty history and so would not actually check that our migrations ran.
        assertThat(succeeded).containsExactly("1", "2", "3", "4", "5");
        assertThat(failed).as("a failed migration must never be left in the history").isZero();
    }

    @Test
    @DisplayName("NFR-5: Hibernate created no tables -- Flyway owns the schema exclusively")
    void hibernate_createdNoTables() {
        // ddl-auto: none is configuration, and configuration is not behaviour until something
        // checks it. Flipping it to `update` would not fail any other test in this suite.
        //
        // The expected list is maintained by hand rather than derived from the entity classes,
        // deliberately: deriving it from JPA metadata would make this test pass even if ddl-auto
        // silently started creating tables again, because Hibernate's own view of "what tables
        // should exist" is exactly what this test must not trust. Every name below must trace to a
        // Flyway migration (V2-V5 as of M1.3).
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "department", "employee", "flyway_schema_history", "job_role", "location");
    }

    @Test
    @Transactional
    @DisplayName("FR-3.2: a gist EXCLUDE constraint rejects overlapping intervals for one employee")
    void exclusionConstraint_rejectsOverlappingIntervalsForSameEmployee() {
        // This is the test that matters for FR-3.2, and it is deliberately stronger than asserting
        // that the btree_gist extension is installed. "Installed" is necessary but not sufficient:
        // what the design actually depends on is that a GiST exclusion constraint can MIX an
        // equality column (BIGINT) with a range column (daterange) -- which is precisely what
        // btree_gist enables -- and that PostgreSQL then rejects an overlapping insert.
        //
        // @Transactional pins every statement below to one connection (so the table is visible
        // across JdbcTemplate calls) and rolls the whole thing back, leaving no residue. DDL is
        // transactional in PostgreSQL, so the table disappears with the rollback.
        jdbcTemplate.execute("""
                CREATE TABLE fr32_interval_probe (
                    employee_id    BIGINT NOT NULL,
                    effective_from DATE   NOT NULL,
                    effective_to   DATE,
                    EXCLUDE USING gist (
                        employee_id WITH =,
                        daterange(effective_from, effective_to) WITH &&
                    )
                )
                """);

        jdbcTemplate.update(
                "INSERT INTO fr32_interval_probe VALUES (1, DATE '2026-01-01', DATE '2026-06-01')");

        assertThatCode(() -> {
            // Adjacent, not overlapping: daterange is half-open, so [Jan,Jun) and [Jun,) do not
            // intersect. This is exactly how a raise closes the previous record (FR-3.2).
            jdbcTemplate.update(
                    "INSERT INTO fr32_interval_probe VALUES (1, DATE '2026-06-01', NULL)");
            // A different employee may hold an interval covering the same dates.
            jdbcTemplate.update(
                    "INSERT INTO fr32_interval_probe VALUES (2, DATE '2026-01-01', DATE '2026-06-01')");
        }).as("legitimate salary history must not be blocked").doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO fr32_interval_probe VALUES (1, DATE '2026-03-01', DATE '2026-09-01')"))
                .as("an overlapping interval for the same employee must be rejected by the database")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FR-2.3: pg_trgm similarity is available for indexed free-text employee search")
    void pgTrgm_supportsSimilaritySearch() {
        // V1 creates two extensions; both should carry the same proof. Calling similarity() is a
        // better check than counting rows in pg_extension, because it exercises the function the
        // FR-2.3 search index will actually use rather than merely observing that a row exists.
        Double similarity = jdbcTemplate.queryForObject(
                "SELECT similarity('Katherine', 'Kathryn')", Double.class);

        assertThat(similarity).isNotNull().isBetween(0.0d, 1.0d);
    }

    @Test
    @DisplayName("ADR-0001: percentile_cont is available, as assumed when choosing PostgreSQL")
    void percentileCont_isAvailable() {
        // Labelled against ADR-0001 rather than FR-4.1: FR-4.1 requires mean/median/p25/p75 over a
        // filtered slice in base currency, none of which exists yet. Claiming FR-4.1 here would
        // assert traceability (NFR-8) to a requirement nobody has started. This is a capability
        // probe for the database choice, which is what ADR-0001 actually rests on.
        //
        // 2.5 is exactly representable in binary64 and every intermediate in this computation is
        // exact, so exact equality is safe here. A fixture yielding e.g. 1/3 would need isCloseTo.
        Double median = jdbcTemplate.queryForObject(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) FROM (VALUES (1),(2),(3),(4)) AS t(v)",
                Double.class);

        assertThat(median).isEqualTo(2.5d);
    }
}
