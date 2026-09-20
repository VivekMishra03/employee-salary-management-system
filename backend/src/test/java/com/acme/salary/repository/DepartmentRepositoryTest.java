package com.acme.salary.repository;

import com.acme.salary.model.Department;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.2: department is the first reference table (requirements.md section 6.2). Persistence is
 * exercised against a real PostgreSQL instance (ADR-0001), migrated by Flyway -- {@code
 * @AutoConfigureTestDatabase(replace = NONE)} stops Spring Boot substituting an in-memory database
 * and silently skipping the migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class DepartmentRepositoryTest {

    @Autowired
    private DepartmentRepository repository;

    @Test
    @DisplayName("a department round-trips with its natural key and cost center")
    void save_thenFindById_returnsTheSameDepartment() {
        Department engineering = new Department("ENG", "Engineering", null, "CC-100");

        Department saved = repository.save(engineering);
        Optional<Department> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("ENG");
        assertThat(found.get().getName()).isEqualTo("Engineering");
        assertThat(found.get().getCostCenter()).isEqualTo("CC-100");
        assertThat(found.get().getParentDepartmentId()).isNull();
    }

    @Test
    @DisplayName("requirements.md 6.2: department code is unique")
    void save_withDuplicateCode_violatesUniqueConstraint() {
        repository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-100"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(new Department("ENG", "Engineering (duplicate)", null, "CC-200")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_department_code");
    }

    @Test
    @DisplayName("requirements.md 6.2: parent_department_id supports a self-referencing org hierarchy")
    void save_withParentDepartment_persistsTheHierarchyLink() {
        Department engineering = repository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-100"));
        Department platform = repository.saveAndFlush(
                new Department("ENG-PLAT", "Platform Engineering", engineering.getId(), "CC-101"));

        Optional<Department> found = repository.findById(platform.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getParentDepartmentId()).isEqualTo(engineering.getId());
    }
}
