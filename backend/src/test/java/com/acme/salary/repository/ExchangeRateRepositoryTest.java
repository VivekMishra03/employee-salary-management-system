package com.acme.salary.repository;

import com.acme.salary.model.ExchangeRate;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.5: exchange_rate (requirements.md section 6.2, FR-3.5). Rates are a stored, dated table --
 * never a hardcoded constant and never a live third-party call -- and a lookup takes the most
 * recent rate on or before a given date.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class ExchangeRateRepositoryTest {

    @Autowired
    private ExchangeRateRepository repository;

    private ExchangeRate eurToUsd(String rate, LocalDate on) {
        return new ExchangeRate("EUR", "USD", new BigDecimal(rate), on, "seed");
    }

    @Test
    @DisplayName("an exchange rate round-trips at NUMERIC(18,8) precision")
    void save_thenFindById_preservesEightDecimalPlaces() {
        ExchangeRate saved = repository.saveAndFlush(eurToUsd("1.08123456", LocalDate.of(2026, 1, 1)));

        Optional<ExchangeRate> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getFromCurrency()).isEqualTo("EUR");
        assertThat(found.get().getToCurrency()).isEqualTo("USD");
        // FX precision differs from money precision (section 6.2): all eight places must survive.
        assertThat(found.get().getRate()).isEqualByComparingTo("1.08123456");
        assertThat(found.get().getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(found.get().getSource()).isEqualTo("seed");
    }

    @Test
    @DisplayName("requirements.md 6.2: (from_currency, to_currency, effective_date) is unique")
    void save_withDuplicatePairAndDate_violatesUniqueConstraint() {
        repository.saveAndFlush(eurToUsd("1.08000000", LocalDate.of(2026, 1, 1)));

        assertThatThrownBy(() -> repository.saveAndFlush(eurToUsd("1.09000000", LocalDate.of(2026, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_exchange_rate_pair_date");
    }

    @Test
    @DisplayName("FR-3.5: lookup returns the most recent rate on or before the given date")
    void findLatestOnOrBefore_returnsMostRecentEarlierRate() {
        repository.saveAndFlush(eurToUsd("1.08000000", LocalDate.of(2026, 1, 1)));
        repository.saveAndFlush(eurToUsd("1.09000000", LocalDate.of(2026, 2, 1)));
        repository.saveAndFlush(eurToUsd("1.10000000", LocalDate.of(2026, 3, 1)));

        Optional<ExchangeRate> found = repository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        "EUR", "USD", LocalDate.of(2026, 2, 15));

        assertThat(found).isPresent();
        assertThat(found.get().getRate()).isEqualByComparingTo("1.09000000");
    }

    @Test
    @DisplayName("FR-3.5: a rate effective exactly on the requested date is included")
    void findLatestOnOrBefore_includesRateOnTheExactDate() {
        repository.saveAndFlush(eurToUsd("1.08000000", LocalDate.of(2026, 1, 1)));
        repository.saveAndFlush(eurToUsd("1.09000000", LocalDate.of(2026, 2, 1)));

        Optional<ExchangeRate> found = repository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        "EUR", "USD", LocalDate.of(2026, 2, 1));

        assertThat(found).isPresent();
        assertThat(found.get().getRate()).isEqualByComparingTo("1.09000000");
    }

    @Test
    @DisplayName("FR-3.5: no rate on or before the date means no result, never a later rate")
    void findLatestOnOrBefore_withOnlyLaterRates_returnsEmpty() {
        repository.saveAndFlush(eurToUsd("1.09000000", LocalDate.of(2026, 2, 1)));

        Optional<ExchangeRate> found = repository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        "EUR", "USD", LocalDate.of(2025, 12, 31));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("FR-3.5: lookup is scoped to the requested currency pair")
    void findLatestOnOrBefore_ignoresOtherPairs() {
        repository.saveAndFlush(new ExchangeRate("GBP", "USD", new BigDecimal("1.27000000"),
                LocalDate.of(2026, 2, 1), "seed"));

        Optional<ExchangeRate> found = repository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        "EUR", "USD", LocalDate.of(2026, 2, 15));

        assertThat(found).isEmpty();
    }
}
