package com.acme.salary.repository;

import com.acme.salary.model.JobRole;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.2: job_role (requirements.md section 6.2). job_level is a real column, not parsed out of the
 * title string, because FR-4.2 and FR-4.5 group analytics by it directly.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class JobRoleRepositoryTest {

    @Autowired
    private JobRoleRepository repository;

    @Test
    @DisplayName("a job role round-trips with its title, family and level")
    void save_thenFindById_returnsTheSameJobRole() {
        JobRole role = new JobRole("Senior Software Engineer", "Engineering", "L5");

        JobRole saved = repository.save(role);
        Optional<JobRole> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Senior Software Engineer");
        assertThat(found.get().getJobFamily()).isEqualTo("Engineering");
        assertThat(found.get().getJobLevel()).isEqualTo("L5");
    }

    @Test
    @DisplayName("requirements.md 6.2: (title, job_level) is unique")
    void save_withDuplicateTitleAndLevel_violatesUniqueConstraint() {
        repository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4"));

        // jobFamily deliberately differs from the first row. Two byte-identical rows would still
        // collide under a constraint covering all three columns, which is a strictly narrower rule
        // than the spec asks for -- this shape is what actually pins the constraint to the pair.
        assertThatThrownBy(() ->
                repository.saveAndFlush(new JobRole("Software Engineer", "Platform", "L4")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_job_role_title_level");
    }

    @Test
    @DisplayName("the same title at a different level is a distinct role, not a duplicate")
    void save_withSameTitleDifferentLevel_isAllowed() {
        // "Software Engineer" exists at multiple levels (L3, L4, L5...) -- that is the whole point
        // of job_level being a real column rather than folded into the title.
        repository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L3"));

        assertThatCode(() ->
                repository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")))
                .as("same title, different level -- must not collide")
                .doesNotThrowAnyException();
    }
}
