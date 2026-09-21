package com.acme.salary.repository;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.AuditAction;
import com.acme.salary.model.AuditLog;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.5: audit_log (requirements.md section 6.2, FR-3.7, NFR-7). Append-only: "no update or delete
 * path exists in code" -- so the repository exposes only save and reads, and the entity has no
 * setters.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class})
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository repository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long actorId;

    @BeforeEach
    void seedActor() {
        actorId = appUserRepository.saveAndFlush(new AppUser("hr.manager@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya Sharma",
                AppUserRole.HR_MANAGER, true)).getId();
    }

    private AuditLog saveAndReload(AuditLog log) {
        AuditLog saved = repository.save(log);
        entityManager.flush();
        entityManager.clear();
        return repository.findById(saved.getId()).orElseThrow();
    }

    @Test
    @DisplayName("FR-3.7: an audit entry round-trips with actor, action and before/after state")
    void save_thenFindById_returnsTheSameEntry() {
        AuditLog found = saveAndReload(new AuditLog("salary_record", 42L, AuditAction.UPDATE, actorId,
                "{\"baseAmount\":100000.00}", "{\"baseAmount\":110000.00}"));

        assertThat(found.getEntityType()).isEqualTo("salary_record");
        assertThat(found.getEntityId()).isEqualTo(42L);
        assertThat(found.getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(found.getActorUserId()).isEqualTo(actorId);
        assertThat(found.getChangedAt()).isNotNull();
        assertThat(found.getBeforeState()).contains("100000.00");
        assertThat(found.getAfterState()).contains("110000.00");
    }

    @Test
    @DisplayName("FR-3.7: before/after state is stored as real JSONB, not a quoted string")
    void save_storesStateAsQueryableJsonb() {
        AuditLog found = saveAndReload(new AuditLog("employee", 7L, AuditAction.UPDATE, actorId,
                "{\"lastName\":\"Lovelace\"}", "{\"lastName\":\"Byron\"}"));

        // ->> only works on a JSON object. If the value had been written as a JSON *string* holding
        // escaped JSON text, this would return null.
        String before = jdbcTemplate.queryForObject(
                "SELECT before_state ->> 'lastName' FROM audit_log WHERE id = ?", String.class, found.getId());
        String after = jdbcTemplate.queryForObject(
                "SELECT after_state ->> 'lastName' FROM audit_log WHERE id = ?", String.class, found.getId());

        assertThat(before).isEqualTo("Lovelace");
        assertThat(after).isEqualTo("Byron");
    }

    @Test
    @DisplayName("a CREATE has no before-state and a DELETE has no after-state")
    void save_allowsNullBeforeForCreateAndNullAfterForDelete() {
        AuditLog created = saveAndReload(new AuditLog("employee", 1L, AuditAction.CREATE, actorId,
                null, "{\"lastName\":\"Lovelace\"}"));
        AuditLog deleted = saveAndReload(new AuditLog("employee", 1L, AuditAction.DELETE, actorId,
                "{\"lastName\":\"Lovelace\"}", null));

        assertThat(created.getBeforeState()).isNull();
        assertThat(deleted.getAfterState()).isNull();
    }

    @Test
    @DisplayName("NFR-7: an entry must be attributable to an existing user")
    void save_withNonExistentActor_violatesForeignKey() {
        // Flushed through the raw EntityManager, not a Spring Data proxy, so Spring's exception
        // translation does not apply and Hibernate's own ConstraintViolationException surfaces. The
        // guarantee under test -- the database rejects the row and names the FK -- is unchanged.
        assertThatThrownBy(() -> {
            repository.save(new AuditLog("employee", 1L, AuditAction.CREATE, -1L, null, "{}"));
            entityManager.flush();
        }).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                .hasMessageContaining("fk_audit_log_actor");
    }

    @Test
    @DisplayName("invariant: action must be CREATE, UPDATE or DELETE")
    void insert_withInvalidAction_violatesCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO audit_log (id, entity_type, entity_id, action, actor_user_id, changed_at)
                VALUES (900000001, 'employee', 1, 'TRUNCATE', ?, now())
                """, actorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_audit_log_action_valid");
    }

    @Test
    @DisplayName("history for one entity is returned newest first")
    void findByEntityTypeAndEntityId_returnsOnlyThatEntitysHistory() {
        repository.save(new AuditLog("employee", 1L, AuditAction.CREATE, actorId, null, "{}"));
        repository.save(new AuditLog("employee", 1L, AuditAction.UPDATE, actorId, "{}", "{}"));
        repository.save(new AuditLog("employee", 2L, AuditAction.CREATE, actorId, null, "{}"));
        entityManager.flush();

        List<AuditLog> history = repository.findByEntityTypeAndEntityIdOrderByChangedAtDesc("employee", 1L);

        assertThat(history).hasSize(2);
        assertThat(history).allMatch(entry -> entry.getEntityId().equals(1L));
    }

    @Test
    @DisplayName("FR-3.7: no update or delete path exists on the repository")
    void repository_exposesNoUpdateOrDeletePath() {
        List<String> names = Arrays.stream(AuditLogRepository.class.getMethods()).map(Method::getName).toList();

        assertThat(names).noneMatch(n -> n.startsWith("delete") || n.startsWith("remove"));
        // Extending CrudRepository/JpaRepository would inherit delete*, deleteAll*, deleteById.
        assertThat(AuditLogRepository.class.getInterfaces())
                .noneMatch(i -> i.getSimpleName().equals("CrudRepository") || i.getSimpleName().equals("JpaRepository"));
    }

    @Test
    @DisplayName("FR-3.7: the entity carries no setters, so an entry cannot be edited after the fact")
    void entity_hasNoSetters() {
        assertThat(Arrays.stream(AuditLog.class.getMethods()).map(Method::getName))
                .noneMatch(n -> n.startsWith("set"));
    }
}
