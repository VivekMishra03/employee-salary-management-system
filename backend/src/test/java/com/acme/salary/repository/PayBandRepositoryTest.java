package com.acme.salary.repository;

import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.model.PayBand;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.5: pay_band (requirements.md section 6.2), the input to FR-4.4 pay-band adherence. Keyed by
 * role AND location because a band is meaningless without a market (section 6.3, decision 6).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class PayBandRepositoryTest {

    @Autowired
    private PayBandRepository repository;
    @Autowired
    private JobRoleRepository jobRoleRepository;
    @Autowired
    private LocationRepository locationRepository;

    private Long jobRoleId;
    private Long locationId;

    @BeforeEach
    void seedReferenceData() {
        jobRoleId = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        locationId = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
    }

    private PayBand band(String min, String mid, String max, LocalDate from) {
        return new PayBand(jobRoleId, locationId, "USD", new BigDecimal(min), new BigDecimal(mid),
                new BigDecimal(max), from, null);
    }

    @Test
    @DisplayName("a pay band round-trips with its min/mid/max and effective range")
    void save_thenFindById_returnsTheSamePayBand() {
        PayBand saved = repository.saveAndFlush(
                band("100000.00", "125000.00", "150000.00", LocalDate.of(2026, 1, 1)));

        Optional<PayBand> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        PayBand b = found.get();
        assertThat(b.getJobRoleId()).isEqualTo(jobRoleId);
        assertThat(b.getLocationId()).isEqualTo(locationId);
        assertThat(b.getCurrencyCode()).isEqualTo("USD");
        assertThat(b.getMinAmount()).isEqualByComparingTo("100000.00");
        assertThat(b.getMidAmount()).isEqualByComparingTo("125000.00");
        assertThat(b.getMaxAmount()).isEqualByComparingTo("150000.00");
        assertThat(b.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(b.getEffectiveTo()).isNull();
    }

    @Test
    @DisplayName("requirements.md 6.2: (job_role_id, location_id, effective_from) is unique")
    void save_withDuplicateRoleLocationAndStart_violatesUniqueConstraint() {
        repository.saveAndFlush(band("100000.00", "125000.00", "150000.00", LocalDate.of(2026, 1, 1)));

        // Different amounts, same key: pins the constraint to the triple, not to the whole row.
        assertThatThrownBy(() -> repository.saveAndFlush(
                band("110000.00", "130000.00", "160000.00", LocalDate.of(2026, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_pay_band_role_location_from");
    }

    @Test
    @DisplayName("a new band for the same role and location with a later start is allowed")
    void save_withSameRoleLocationDifferentStart_isAllowed() {
        repository.saveAndFlush(band("100000.00", "125000.00", "150000.00", LocalDate.of(2026, 1, 1)));

        assertThatCode(() -> repository.saveAndFlush(
                band("105000.00", "130000.00", "155000.00", LocalDate.of(2027, 1, 1))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requirements.md 6.2: min must not exceed mid")
    void save_withMinAboveMid_violatesCheckConstraint() {
        assertThatThrownBy(() -> repository.saveAndFlush(
                band("130000.00", "125000.00", "150000.00", LocalDate.of(2026, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_pay_band_min_mid_max");
    }

    @Test
    @DisplayName("requirements.md 6.2: mid must not exceed max")
    void save_withMidAboveMax_violatesCheckConstraint() {
        assertThatThrownBy(() -> repository.saveAndFlush(
                band("100000.00", "155000.00", "150000.00", LocalDate.of(2026, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_pay_band_min_mid_max");
    }

    @Test
    @DisplayName("requirements.md 6.2: job_role_id must reference an existing job role")
    void save_withNonExistentJobRole_violatesForeignKey() {
        PayBand orphan = new PayBand(-1L, locationId, "USD", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("3.00"), LocalDate.of(2026, 1, 1), null);

        assertThatThrownBy(() -> repository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_pay_band_job_role");
    }

    @Test
    @DisplayName("requirements.md 6.2: location_id must reference an existing location")
    void save_withNonExistentLocation_violatesForeignKey() {
        PayBand orphan = new PayBand(jobRoleId, -1L, "USD", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("3.00"), LocalDate.of(2026, 1, 1), null);

        assertThatThrownBy(() -> repository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_pay_band_location");
    }
}
