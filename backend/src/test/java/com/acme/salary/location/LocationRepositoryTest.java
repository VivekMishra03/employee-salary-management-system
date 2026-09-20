package com.acme.salary.location;

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
 * M1.2: location (requirements.md section 6.2) — the link between an employee and their local
 * currency, used by both FR-3.4 (salary stored in local currency) and FR-4.2 (pay comparison by
 * country).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class LocationRepositoryTest {

    @Autowired
    private LocationRepository repository;

    @Test
    @DisplayName("a location round-trips with its country, city and currency")
    void save_thenFindById_returnsTheSameLocation() {
        Location berlin = new Location("DE", "Germany", "Berlin", "EUR");

        Location saved = repository.save(berlin);
        Optional<Location> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCountryCode()).isEqualTo("DE");
        assertThat(found.get().getCountryName()).isEqualTo("Germany");
        assertThat(found.get().getCity()).isEqualTo("Berlin");
        assertThat(found.get().getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("requirements.md 6.2: (country_code, city) is unique")
    void save_withDuplicateCountryAndCity_violatesUniqueConstraint() {
        repository.saveAndFlush(new Location("US", "United States", "Austin", "USD"));

        // currencyCode deliberately differs from the first row. Two byte-identical rows would
        // still collide under a constraint on all four columns, which is a strictly narrower rule
        // than the spec asks for -- this shape is what actually pins the constraint to the pair.
        assertThatThrownBy(() ->
                repository.saveAndFlush(new Location("US", "United States", "Austin", "CAD")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_location_country_city");
    }

    @Test
    @DisplayName("the same city name in different countries is not a duplicate")
    void save_withSameCityDifferentCountry_isAllowed() {
        // Two real ACME-plausible locations sharing a city name: Cambridge, UK and Cambridge, US.
        // The unique constraint is on the pair, not on city alone.
        repository.saveAndFlush(new Location("GB", "United Kingdom", "Cambridge", "GBP"));

        assertThatCode(() ->
                repository.saveAndFlush(new Location("US", "United States", "Cambridge", "USD")))
                .as("different country, same city -- must not collide")
                .doesNotThrowAnyException();
    }
}
