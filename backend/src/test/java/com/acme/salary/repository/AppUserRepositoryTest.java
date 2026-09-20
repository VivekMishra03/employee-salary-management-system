package com.acme.salary.repository;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.4 (built ahead of its original slot): requirements.md section 6.2 {@code app_user}.
 *
 * <p>Built now, before {@code salary_record}, because {@code salary_record.created_by} is a
 * required FK to this table -- the dependency graph, not the milestone sketch, decides build
 * order (see docs/adr, "entity build order" note).
 *
 * <p>Only persistence is in scope here. BCrypt hashing, login and JWT issuance are FR-1 / M2; this
 * table exists so {@code salary_record} has something to reference.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class})
class AppUserRepositoryTest {

    @Autowired
    private AppUserRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("an app_user round-trips with its role and enabled flag")
    void save_thenFindById_returnsTheSameAppUser() {
        AppUser saved = repository.saveAndFlush(
                new AppUser("hr.manager@acme.example", "$2a$10$placeholderBcryptHashValueXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
                        "Priya Sharma", AppUserRole.HR_MANAGER, true));

        Optional<AppUser> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        AppUser u = found.get();
        assertThat(u.getEmail()).isEqualTo("hr.manager@acme.example");
        assertThat(u.getPasswordHash()).startsWith("$2a$10$");
        assertThat(u.getFullName()).isEqualTo("Priya Sharma");
        assertThat(u.getRole()).isEqualTo(AppUserRole.HR_MANAGER);
        assertThat(u.isEnabled()).isTrue();
        assertThat(u.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("requirements.md 6.2: email is unique")
    void save_withDuplicateEmail_violatesUniqueConstraint() {
        repository.saveAndFlush(new AppUser("hr.manager@acme.example", "$2a$10$hashOneXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
                "Priya Sharma", AppUserRole.HR_MANAGER, true));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new AppUser("hr.manager@acme.example", "$2a$10$hashTwoXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
                        "Different Name", AppUserRole.HR_MANAGER, true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_app_user_email");
    }

    @Test
    @DisplayName("invariant: role must be one of the documented values")
    void insert_withInvalidRole_violatesCheckConstraint() {
        // AppUserRole's Java enum makes an invalid role unconstructable through the entity's own
        // API, so this deliberately bypasses JPA with a raw INSERT -- the same route a manual
        // correction or a future migration could take.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO app_user (id, email, password_hash, full_name, role, enabled, created_at)
                VALUES (900000001, 'probe@acme.example', '$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
                    'Probe User', 'SUPERADMIN', true, now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_app_user_role_valid");
    }
}
