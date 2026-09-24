package com.acme.salary.service;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.TrendPoint;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.readmodel.TrendAnalyticsRepository;
import com.acme.salary.readmodel.TrendInterval;
import com.acme.salary.readmodel.TrendRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FR-4.6 / FR-4.7: request validation and labelling for the trend, without a database. The trend has
 * no "today" (both dates come from the caller), so the service takes no Clock.
 */
@ExtendWith(MockitoExtension.class)
class TrendServiceTest {

    private static final EmployeeFilter FILTER = new EmployeeFilter(null, 3L, null, null, null, null);

    @Mock
    private TrendAnalyticsRepository repository;

    private TrendService service() {
        return new TrendService(repository);
    }

    private static RequestValidationException rejected(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(RequestValidationException.class);
        return (RequestValidationException) thrown;
    }

    @Test
    @DisplayName("FR-4.6: monthly periods are labelled 2026-03 and carry payroll and increase through unchanged")
    void monthly_labelsAndMapsThePeriods() {
        when(repository.trend(TrendInterval.MONTH, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30), FILTER))
                .thenReturn(List.of(
                        new TrendRow(LocalDate.of(2026, 3, 1), new BigDecimal("843000.00"), new BigDecimal("15.00")),
                        new TrendRow(LocalDate.of(2026, 4, 1), new BigDecimal("800000.00"), null)));

        List<TrendPoint> points = service().trend("2026-03-01", "2026-04-30", "MONTH", FILTER);

        assertThat(points).containsExactly(
                new TrendPoint("2026-03", new BigDecimal("843000.00"), new BigDecimal("15.00")),
                new TrendPoint("2026-04", new BigDecimal("800000.00"), null));
    }

    @Test
    @DisplayName("FR-4.6: quarterly periods are labelled 2026-Q1 and yearly ones 2026; the interval is case-insensitive")
    void quarterlyAndYearly_labels() {
        when(repository.trend(TrendInterval.QUARTER, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), FILTER))
                .thenReturn(List.of(
                        new TrendRow(LocalDate.of(2026, 1, 1), new BigDecimal("1.00"), null),
                        new TrendRow(LocalDate.of(2026, 4, 1), new BigDecimal("2.00"), null)));
        when(repository.trend(TrendInterval.YEAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), FILTER))
                .thenReturn(List.of(new TrendRow(LocalDate.of(2026, 1, 1), new BigDecimal("3.00"), null)));

        assertThat(service().trend("2026-01-01", "2026-06-30", "quarter", FILTER)).extracting(TrendPoint::period)
                .containsExactly("2026-Q1", "2026-Q2");
        assertThat(service().trend("2026-01-01", "2026-06-30", "Year", FILTER)).extracting(TrendPoint::period)
                .containsExactly("2026");
    }

    // ---- span limit: at most 120 periods ---------------------------------------------------

    @Test
    @DisplayName("FR-4.6: exactly 120 months (2016-01 to 2025-12) is accepted")
    void span_of120Months_isAccepted() {
        when(repository.trend(any(), any(), any(), any())).thenReturn(List.of());

        assertThatCode(() -> service().trend("2016-01-01", "2025-12-31", "MONTH", FILTER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FR-4.6: 121 months (one day into January 2026) is rejected as TREND_SPAN_TOO_LARGE")
    void span_of121Months_isRejected() {
        RequestValidationException e = rejected(
                () -> service().trend("2016-01-01", "2026-01-01", "MONTH", FILTER));

        assertThat(e.getCode()).isEqualTo("TREND_SPAN_TOO_LARGE");
        assertThat(e.getField()).isEqualTo("to");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("FR-4.6: the limit counts periods, not days: a range starting mid-month counts that whole month")
    void span_countsWholeBucketsTouched() {
        // 2016-01-31 .. 2026-01-01 touches 121 calendar months.
        assertThat(rejected(() -> service().trend("2016-01-31", "2026-01-01", "MONTH", FILTER)).getCode())
                .isEqualTo("TREND_SPAN_TOO_LARGE");
    }

    @Test
    @DisplayName("FR-4.6: 120 quarters (30 years) is accepted and 121 quarters is rejected")
    void span_quarterBoundary() {
        when(repository.trend(any(), any(), any(), any())).thenReturn(List.of());

        assertThatCode(() -> service().trend("1996-01-01", "2025-12-31", "QUARTER", FILTER)).doesNotThrowAnyException();
        assertThat(rejected(() -> service().trend("1995-12-31", "2025-12-31", "QUARTER", FILTER)).getCode())
                .isEqualTo("TREND_SPAN_TOO_LARGE");
    }

    @Test
    @DisplayName("FR-4.6: 120 years is accepted and 121 years is rejected")
    void span_yearBoundary() {
        when(repository.trend(any(), any(), any(), any())).thenReturn(List.of());

        assertThatCode(() -> service().trend("1906-01-01", "2025-12-31", "YEAR", FILTER)).doesNotThrowAnyException();
        assertThat(rejected(() -> service().trend("1905-06-01", "2025-12-31", "YEAR", FILTER)).getCode())
                .isEqualTo("TREND_SPAN_TOO_LARGE");
    }

    // ---- calendar range: years 1900 through 2200 only ---------------------------------------

    @ParameterizedTest(name = "from \"{0}\" is outside the supported calendar range")
    @ValueSource(strings = {"1899-12-31", "2201-01-01", "-999999999-01-01", "+999999999-12-31"})
    @DisplayName("FR-4.6: a from outside years 1900-2200 is DATE_OUT_OF_RANGE on from, and the message does not echo it")
    void fromOutsideCalendarRange_isRejected(String from) {
        RequestValidationException e = rejected(() -> service().trend(from, "2200-12-31", "YEAR", FILTER));

        assertThat(e.getCode()).isEqualTo("DATE_OUT_OF_RANGE");
        assertThat(e.getField()).isEqualTo("from");
        assertThat(e.getMessage()).doesNotContain(from);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "to \"{0}\" is outside the supported calendar range")
    @ValueSource(strings = {"1899-12-31", "2201-01-01", "-999999999-01-01", "+999999999-12-31"})
    @DisplayName("FR-4.6: a to outside years 1900-2200 is DATE_OUT_OF_RANGE on to")
    void toOutsideCalendarRange_isRejected(String to) {
        RequestValidationException e = rejected(() -> service().trend("1900-01-01", to, "YEAR", FILTER));

        // Only a `to` before the fixed `from` is also out of order; the range check must come first.
        assertThat(e.getCode()).isEqualTo("DATE_OUT_OF_RANGE");
        assertThat(e.getField()).isEqualTo("to");
        assertThat(e.getMessage()).doesNotContain(to);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("FR-4.6: 1900-01-01 and 2200-12-31 are the inclusive boundaries and are accepted")
    void calendarRangeBoundaries_areAccepted() {
        when(repository.trend(any(), any(), any(), any())).thenReturn(List.of());

        assertThatCode(() -> service().trend("1900-01-01", "1900-01-31", "MONTH", FILTER)).doesNotThrowAnyException();
        assertThatCode(() -> service().trend("2200-12-01", "2200-12-31", "MONTH", FILTER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FR-4.6: the overflow range (-999999999 to +999999999 by month) is rejected before any query")
    void hugeRange_byMonth_isRejectedWithoutQuerying() {
        RequestValidationException e = rejected(
                () -> service().trend("-999999999-01-01", "+999999999-12-31", "MONTH", FILTER));

        assertThat(e.getCode()).isEqualTo("DATE_OUT_OF_RANGE");
        verifyNoInteractions(repository);
    }

    // ---- validation ------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: from after to is INVALID_DATE_RANGE; from equal to to is allowed")
    void fromAfterTo_isRejected() {
        RequestValidationException e = rejected(() -> service().trend("2026-05-02", "2026-05-01", "MONTH", FILTER));
        assertThat(e.getCode()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(e.getField()).isEqualTo("from");
        verifyNoInteractions(repository);

        when(repository.trend(any(), any(), any(), any())).thenReturn(List.of());
        assertThatCode(() -> service().trend("2026-05-01", "2026-05-01", "MONTH", FILTER)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "from \"{0}\" is rejected")
    @NullAndEmptySource
    @ValueSource(strings = {"2026-02-30", "15/03/2026", "yesterday", "2026-3-1"})
    @DisplayName("FR-4.6: a missing or invalid from is INVALID_FROM, and the message does not echo it")
    void invalidFrom_isRejected(String from) {
        RequestValidationException e = rejected(() -> service().trend(from, "2026-06-30", "MONTH", FILTER));

        assertThat(e.getCode()).isEqualTo("INVALID_FROM");
        assertThat(e.getField()).isEqualTo("from");
        if (from != null && !from.isEmpty()) {
            assertThat(e.getMessage()).doesNotContain(from);
        }
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "to \"{0}\" is rejected")
    @NullAndEmptySource
    @ValueSource(strings = {"2026-13-01", "tomorrow"})
    @DisplayName("FR-4.6: a missing or invalid to is INVALID_TO")
    void invalidTo_isRejected(String to) {
        RequestValidationException e = rejected(() -> service().trend("2026-01-01", to, "MONTH", FILTER));

        assertThat(e.getCode()).isEqualTo("INVALID_TO");
        assertThat(e.getField()).isEqualTo("to");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "interval \"{0}\" is rejected")
    @NullAndEmptySource
    @ValueSource(strings = {"WEEK", "DAY"})
    @DisplayName("FR-4.6: a missing or unknown interval is INVALID_INTERVAL")
    void invalidInterval_isRejected(String interval) {
        RequestValidationException e = rejected(() -> service().trend("2026-01-01", "2026-06-30", interval, FILTER));

        assertThat(e.getCode()).isEqualTo("INVALID_INTERVAL");
        assertThat(e.getField()).isEqualTo("interval");
        verifyNoInteractions(repository);
    }
}
