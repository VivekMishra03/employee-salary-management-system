package com.acme.salary.repository;

import com.acme.salary.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    /**
     * FR-3.5: "the most recent rate on or before a given date". Inclusive of the date itself, and
     * never falls forward to a later rate -- an empty result means no rate was known yet.
     */
    Optional<ExchangeRate> findFirstByFromCurrencyAndToCurrencyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
            String fromCurrency, String toCurrency, LocalDate date);
}
