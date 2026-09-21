package com.acme.salary.service;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.AuditAction;
import com.acme.salary.model.AuditLog;
import com.acme.salary.repository.AppUserRepository;
import com.acme.salary.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-3.7: the audit writer's contract. Kept in its own class, with no {@code @BeforeEach} seeding,
 * on purpose: the "requires a transaction" test must run with {@code NOT_SUPPORTED}, and Spring
 * starts the test-managed transaction from the *method's* attribute -- so a seeding {@code @BeforeEach}
 * in the same class would run with no transaction, auto-commit its data, and leak it into every
 * later test. This class writes nothing outside a transaction, so nothing can leak.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({AuditLogService.class, com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class})
class AuditLogServiceTest {

    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("FR-3.7: an audit entry can only be written inside a mutation's transaction, never on its own")
    void record_outsideATransaction_isRefused() {
        assertThatThrownBy(() -> auditLogService.record("salary_record", 1L, AuditAction.CREATE, 1L, null, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("FR-3.7: inside a transaction the state is serialised to real JSON, dates and nulls included")
    void record_insideATransaction_storesTheStateAsJson() throws Exception {
        Long actor = appUserRepository.saveAndFlush(new AppUser("hr@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya", AppUserRole.HR_MANAGER, true))
                .getId();
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("effectiveFrom", LocalDate.of(2021, 6, 1));
        after.put("effectiveTo", null);

        auditLogService.record("salary_record", 7L, AuditAction.UPDATE, actor, Map.of("x", 1), after);

        List<AuditLog> entries = auditLogRepository.findByEntityTypeAndEntityIdOrderByChangedAtDesc("salary_record", 7L);
        assertThat(entries).hasSize(1);
        assertThat(objectMapper.readTree(entries.get(0).getAfterState()).get("effectiveFrom").asText())
                .isEqualTo("2021-06-01");
        assertThat(objectMapper.readTree(entries.get(0).getAfterState()).get("effectiveTo").isNull()).isTrue();
    }
}
