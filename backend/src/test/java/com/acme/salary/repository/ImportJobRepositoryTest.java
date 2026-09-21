package com.acme.salary.repository;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.ImportError;
import com.acme.salary.model.ImportJob;
import com.acme.salary.model.ImportStatus;
import com.acme.salary.model.ImportType;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.5: import_job and import_error (requirements.md section 6.2, FR-5.6). A failed upload must be
 * diagnosable after the fact, and import_error drives the downloadable error report (FR-5.4).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class})
class ImportJobRepositoryTest {

    @Autowired
    private ImportJobRepository jobRepository;
    @Autowired
    private ImportErrorRepository errorRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void seedUser() {
        userId = appUserRepository.saveAndFlush(new AppUser("hr.manager@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya Sharma",
                AppUserRole.HR_MANAGER, true)).getId();
    }

    @Test
    @DisplayName("an import job round-trips with status, counters and timestamps")
    void save_thenFindById_returnsTheSameJob() {
        ImportJob job = new ImportJob("employees.csv", ImportType.EMPLOYEES, ImportStatus.PENDING, userId);
        Instant started = Instant.parse("2026-01-15T10:00:00Z");
        Instant finished = Instant.parse("2026-01-15T10:00:05Z");
        job.setStatus(ImportStatus.COMPLETED);
        job.setTotalRows(100);
        job.setSuccessRows(97);
        job.setFailedRows(3);
        job.setStartedAt(started);
        job.setFinishedAt(finished);

        ImportJob saved = jobRepository.saveAndFlush(job);
        Optional<ImportJob> found = jobRepository.findById(saved.getId());

        assertThat(found).isPresent();
        ImportJob j = found.get();
        assertThat(j.getFilename()).isEqualTo("employees.csv");
        assertThat(j.getImportType()).isEqualTo(ImportType.EMPLOYEES);
        assertThat(j.getStatus()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(j.getTotalRows()).isEqualTo(100);
        assertThat(j.getSuccessRows()).isEqualTo(97);
        assertThat(j.getFailedRows()).isEqualTo(3);
        assertThat(j.getStartedAt()).isEqualTo(started);
        assertThat(j.getFinishedAt()).isEqualTo(finished);
        assertThat(j.getCreatedBy()).isEqualTo(userId);
    }

    @Test
    @DisplayName("a freshly created job starts with zero counters and no timestamps")
    void newJob_defaultsCountersToZero() {
        ImportJob saved = jobRepository.saveAndFlush(
                new ImportJob("salaries.csv", ImportType.SALARY_CHANGES, ImportStatus.PENDING, userId));

        ImportJob found = jobRepository.findById(saved.getId()).orElseThrow();

        // Set in Java, not by a SQL DEFAULT: Hibernate always writes every column explicitly, so a
        // DEFAULT could never fire (same lesson as employee.fte_ratio).
        assertThat(found.getTotalRows()).isZero();
        assertThat(found.getSuccessRows()).isZero();
        assertThat(found.getFailedRows()).isZero();
        assertThat(found.getStartedAt()).isNull();
        assertThat(found.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("requirements.md 6.2: created_by must reference an existing app_user")
    void save_withNonExistentCreatedBy_violatesForeignKey() {
        ImportJob job = new ImportJob("employees.csv", ImportType.EMPLOYEES, ImportStatus.PENDING, -1L);

        assertThatThrownBy(() -> jobRepository.saveAndFlush(job))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_import_job_created_by");
    }

    @Test
    @DisplayName("invariant: status must be PENDING, PROCESSING, COMPLETED or FAILED")
    void insert_withInvalidStatus_violatesCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO import_job (id, filename, import_type, status, total_rows, success_rows,
                    failed_rows, created_by)
                VALUES (900000001, 'x.csv', 'EMPLOYEES', 'PAUSED', 0, 0, 0, ?)
                """, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_import_job_status_valid");
    }

    @Test
    @DisplayName("invariant: import_type must be EMPLOYEES or SALARY_CHANGES")
    void insert_withInvalidImportType_violatesCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO import_job (id, filename, import_type, status, total_rows, success_rows,
                    failed_rows, created_by)
                VALUES (900000002, 'x.csv', 'PAYSLIPS', 'PENDING', 0, 0, 0, ?)
                """, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_import_job_type_valid");
    }

    @Test
    @DisplayName("FR-5.4: an import error round-trips with its row, column and raw input")
    void saveError_thenFindById_returnsTheSameError() {
        Long jobId = jobRepository.saveAndFlush(
                new ImportJob("employees.csv", ImportType.EMPLOYEES, ImportStatus.FAILED, userId)).getId();

        ImportError saved = errorRepository.saveAndFlush(new ImportError(jobId, 17, "currency_code",
                "UNKNOWN_CURRENCY", "Currency 'XXX' is not a recognised ISO 4217 code", "ACME-000017,Ada,XXX"));
        ImportError found = errorRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getImportJobId()).isEqualTo(jobId);
        assertThat(found.getRowNumber()).isEqualTo(17);
        assertThat(found.getColumnName()).isEqualTo("currency_code");
        assertThat(found.getErrorCode()).isEqualTo("UNKNOWN_CURRENCY");
        assertThat(found.getMessage()).contains("XXX");
        assertThat(found.getRawRow()).isEqualTo("ACME-000017,Ada,XXX");
    }

    @Test
    @DisplayName("requirements.md 6.2: import_job_id must reference an existing job")
    void saveError_withNonExistentJob_violatesForeignKey() {
        ImportError orphan = new ImportError(-1L, 1, null, "MISSING_FIELD", "employee_code is required", null);

        assertThatThrownBy(() -> errorRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_import_error_job");
    }

    @Test
    @DisplayName("FR-5.4: a job's errors come back in row order, and only that job's")
    void findByImportJobIdOrderByRowNumber_returnsThatJobsErrorsInRowOrder() {
        Long jobA = jobRepository.saveAndFlush(
                new ImportJob("a.csv", ImportType.EMPLOYEES, ImportStatus.FAILED, userId)).getId();
        Long jobB = jobRepository.saveAndFlush(
                new ImportJob("b.csv", ImportType.EMPLOYEES, ImportStatus.FAILED, userId)).getId();
        errorRepository.saveAndFlush(new ImportError(jobA, 9, null, "E", "later row", null));
        errorRepository.saveAndFlush(new ImportError(jobA, 2, null, "E", "earlier row", null));
        errorRepository.saveAndFlush(new ImportError(jobB, 5, null, "E", "other job", null));

        List<ImportError> errors = errorRepository.findByImportJobIdOrderByRowNumberAsc(jobA);

        assertThat(errors).extracting(ImportError::getRowNumber).containsExactly(2, 9);
    }
}
