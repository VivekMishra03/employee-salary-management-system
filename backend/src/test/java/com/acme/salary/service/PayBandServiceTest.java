package com.acme.salary.service;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.PayBandEmployee;
import com.acme.salary.dto.PayBandReport;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.model.PayBandAdherence;
import com.acme.salary.readmodel.PayBandAnalyticsRepository;
import com.acme.salary.readmodel.PayBandCountsRow;
import com.acme.salary.readmodel.PayBandRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FR-4.4 / FR-4.7: the service's own rules for the pay-band report -- the as-of date comes from the
 * injected clock, paging and the adherence filter are validated, the page envelope is derived from
 * the counts. Repository stubs are keyed on exact arguments, so a wrong limit, offset or date makes
 * the stub return null and the assertion fail.
 */
@ExtendWith(MockitoExtension.class)
class PayBandServiceTest {

    // 23:59:59 UTC: the last second of the day, so an off-by-one on the date would show.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T23:59:59Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter FILTER = new EmployeeFilter("ada", 3L, "US", null, null, "L4");
    private static final PayBandCountsRow COUNTS = new PayBandCountsRow(3, 20, 5, 2); // 30 in total

    @Mock
    private PayBandAnalyticsRepository repository;

    private PayBandService service() {
        return new PayBandService(repository, CLOCK);
    }

    private static PayBandRow row(long id) {
        return new PayBandRow(id, "ACME-%06d".formatted(id), "Ann Below", "Engineer", "L4", "US", "USD",
                new BigDecimal("79999.99"), new BigDecimal("80000.00"), new BigDecimal("100000.00"),
                new BigDecimal("120000.00"), new BigDecimal("0.80"), PayBandAdherence.BELOW);
    }

    @Test
    @DisplayName("FR-4.4: the report uses today's date from the clock, defaults to page 0 of 20, and carries the four counts")
    void report_defaults_useTheClockPageZeroAndSizeTwenty() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, null, 20, 0L)).thenReturn(List.of(row(1)));

        PayBandReport report = service().report(null, null, null, FILTER);

        assertThat(report.counts().below()).isEqualTo(3);
        assertThat(report.counts().within()).isEqualTo(20);
        assertThat(report.counts().above()).isEqualTo(5);
        assertThat(report.counts().noBand()).isEqualTo(2);
        assertThat(report.employees().page()).isZero();
        assertThat(report.employees().size()).isEqualTo(20);
        assertThat(report.employees().content()).extracting(PayBandEmployee::employeeId).containsExactly(1L);
    }

    @Test
    @DisplayName("FR-4.4: the row is mapped field for field to the DTO")
    void report_mapsTheRowFields() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, null, 20, 0L)).thenReturn(List.of(row(7)));

        PayBandEmployee dto = service().report(null, null, null, FILTER).employees().content().get(0);

        assertThat(dto).isEqualTo(new PayBandEmployee(7, "ACME-000007", "Ann Below", "Engineer", "L4", "US", "USD",
                new BigDecimal("79999.99"), new BigDecimal("80000.00"), new BigDecimal("100000.00"),
                new BigDecimal("120000.00"), new BigDecimal("0.80"), PayBandAdherence.BELOW));
    }

    @Test
    @DisplayName("FR-4.4: page 2 of size 10 asks for offset 20, and the envelope reports 30 elements over 3 pages")
    void report_pageAndSize_becomeOffsetAndEnvelope() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, null, 10, 20L)).thenReturn(List.of(row(21), row(22)));

        PayBandReport report = service().report(null, "2", "10", FILTER);

        assertThat(report.employees().page()).isEqualTo(2);
        assertThat(report.employees().size()).isEqualTo(10);
        assertThat(report.employees().totalElements()).isEqualTo(30);
        assertThat(report.employees().totalPages()).isEqualTo(3);
        assertThat(report.employees().content()).hasSize(2);
    }

    @Test
    @DisplayName("FR-4.4: a partial last page rounds the page count up: 30 elements at size 7 is 5 pages")
    void report_totalPages_roundsUp() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, null, 7, 0L)).thenReturn(List.of());

        assertThat(service().report(null, "0", "7", FILTER).employees().totalPages()).isEqualTo(5);
    }

    @Test
    @DisplayName("FR-4.4: with an adherence filter the envelope counts that class (ABOVE: 5), but the counts stay whole-slice")
    void report_adherenceFilter_totalsThatClassOnly() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, PayBandAdherence.ABOVE, 20, 0L)).thenReturn(List.of());

        PayBandReport report = service().report("above", null, null, FILTER);

        assertThat(report.employees().totalElements()).isEqualTo(5);
        assertThat(report.employees().totalPages()).isEqualTo(1);
        assertThat(report.counts()).isEqualTo(new com.acme.salary.dto.PayBandCounts(3, 20, 5, 2));
    }

    @Test
    @DisplayName("FR-4.4: each adherence class totals from its own count (BELOW 3, WITHIN 20, NO_BAND 2)")
    void report_adherenceFilter_eachClass() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, PayBandAdherence.BELOW, 20, 0L)).thenReturn(List.of());
        when(repository.rows(FILTER, TODAY, PayBandAdherence.WITHIN, 20, 0L)).thenReturn(List.of());
        when(repository.rows(FILTER, TODAY, PayBandAdherence.NO_BAND, 20, 0L)).thenReturn(List.of());

        assertThat(service().report("BELOW", null, null, FILTER).employees().totalElements()).isEqualTo(3);
        assertThat(service().report("WITHIN", null, null, FILTER).employees().totalElements()).isEqualTo(20);
        assertThat(service().report("NO_BAND", null, null, FILTER).employees().totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-4.4: a size above 100 is capped at 100, as the employee list does (FR-2.2)")
    void report_sizeAboveMaximum_isCapped() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, null, 100, 0L)).thenReturn(List.of());

        assertThat(service().report(null, null, "500", FILTER).employees().size()).isEqualTo(100);
    }

    @Test
    @DisplayName("FR-4.4: a large page number does not overflow the offset")
    void report_hugePage_doesNotOverflowTheOffset() {
        when(repository.counts(FILTER, TODAY)).thenReturn(COUNTS);
        when(repository.rows(FILTER, TODAY, null, 100, 2_147_483_647L * 100)).thenReturn(List.of());

        assertThat(service().report(null, "2147483647", "100", FILTER).employees().content()).isEmpty();
    }

    @ParameterizedTest(name = "adherence \"{0}\" is rejected")
    @ValueSource(strings = {"MIDDLE", "below-band", "1"})
    @DisplayName("FR-4.4: an unknown adherence is INVALID_ADHERENCE and the message does not echo it")
    void report_invalidAdherence_isRejected(String adherence) {
        assertThatThrownBy(() -> service().report(adherence, null, null, FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_ADHERENCE");
                    assertThat(e.getField()).isEqualTo("adherence");
                    assertThat(e.getMessage()).doesNotContain(adherence);
                });
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "page \"{0}\" is rejected")
    @ValueSource(strings = {"-1", "abc", "1.5", "99999999999", ""})
    @DisplayName("FR-4.4: a negative or non-numeric page is INVALID_PAGE")
    void report_invalidPage_isRejected(String page) {
        assertThatThrownBy(() -> service().report(null, page, null, FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_PAGE");
                    assertThat(e.getField()).isEqualTo("page");
                });
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "size \"{0}\" is rejected")
    @ValueSource(strings = {"0", "-5", "ten", "1.5", ""})
    @DisplayName("FR-4.4: a size below 1 or non-numeric is INVALID_SIZE")
    void report_invalidSize_isRejected(String size) {
        assertThatThrownBy(() -> service().report(null, null, size, FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_SIZE");
                    assertThat(e.getField()).isEqualTo("size");
                });
        verifyNoInteractions(repository);
    }
}
