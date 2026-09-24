package com.acme.salary.service;

import com.acme.salary.dto.AnalyticsSummary;
import com.acme.salary.dto.DistributionBucket;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.GroupStat;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.readmodel.AnalyticsRepository;
import com.acme.salary.readmodel.BucketRow;
import com.acme.salary.readmodel.GroupBy;
import com.acme.salary.readmodel.GroupRow;
import com.acme.salary.readmodel.SummaryRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-4.1 - FR-4.3, FR-4.7: the service's own rules -- the as-of date comes from the injected clock,
 * and the request parameters are validated. Repository stubs are keyed on the exact as-of date and
 * bucket count, so a wrong value makes the stub return null and the assertion on the result fail.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    // 23:59:59 UTC: the last second of the day, so an off-by-one on the date would show.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T23:59:59Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter FILTER = new EmployeeFilter("ada", 3L, "US", null, null, "L4");

    @Mock
    private AnalyticsRepository repository;

    private AnalyticsService service() {
        return new AnalyticsService(repository, CLOCK);
    }

    // ---- summary ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.1: summary is computed as of today's date from the clock and mapped to the DTO")
    void summary_usesTodayFromTheClock() {
        when(repository.summary(FILTER, TODAY)).thenReturn(new SummaryRow(4, new BigDecimal("350000.00"),
                new BigDecimal("95000.00"), new BigDecimal("90000.00"), new BigDecimal("75000.00"),
                new BigDecimal("110000.00")));

        AnalyticsSummary summary = service().summary(FILTER);

        assertThat(summary).isEqualTo(new AnalyticsSummary(4, new BigDecimal("350000.00"),
                new BigDecimal("95000.00"), new BigDecimal("90000.00"), new BigDecimal("75000.00"),
                new BigDecimal("110000.00"), null));
    }

    @Test
    @DisplayName("FR-4.1: an empty slice maps to zeros and nulls")
    void summary_emptySlice_keepsNulls() {
        when(repository.summary(FILTER, TODAY))
                .thenReturn(new SummaryRow(0, new BigDecimal("0.00"), null, null, null, null));

        AnalyticsSummary summary = service().summary(FILTER);

        assertThat(summary.headcount()).isZero();
        assertThat(summary.totalPayroll()).isEqualByComparingTo("0");
        assertThat(summary.median()).isNull();
    }

    @Test
    @DisplayName("FR-4.1: the as-of date follows the clock, not a cached value")
    void summary_asOfFollowsTheClock() {
        Clock nextDay = Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC);
        when(repository.summary(FILTER, LocalDate.of(2026, 6, 16)))
                .thenReturn(new SummaryRow(1, BigDecimal.ONE, null, null, null, null));

        AnalyticsSummary summary = new AnalyticsService(repository, nextDay).summary(FILTER);

        assertThat(summary.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Assumption 2: the rates as-of date is passed through into the summary, independent of the filter")
    void summary_carriesTheLatestRateDate() {
        when(repository.summary(FILTER, TODAY)).thenReturn(new SummaryRow(1, BigDecimal.ONE, null, null, null, null));
        // A fact about the rate table, deliberately not TODAY: it must not come from the clock.
        when(repository.latestRateDate()).thenReturn(LocalDate.of(2025, 6, 30));

        AnalyticsSummary summary = service().summary(FILTER);

        assertThat(summary.ratesAsOf()).isEqualTo(LocalDate.of(2025, 6, 30));
        // latestRateDate takes no filter or date argument, so the value cannot vary with the slice.
        verify(repository).latestRateDate();
    }

    @Test
    @DisplayName("Assumption 2: no rates in the table leaves ratesAsOf null")
    void summary_withNoRates_hasNullRatesAsOf() {
        when(repository.summary(FILTER, TODAY)).thenReturn(new SummaryRow(1, BigDecimal.ONE, null, null, null, null));
        when(repository.latestRateDate()).thenReturn(null);

        assertThat(service().summary(FILTER).ratesAsOf()).isNull();
    }

    // ---- by group --------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.2: byGroup maps each row, as of today, for the requested grouping")
    void byGroup_mapsRows() {
        when(repository.byGroup(GroupBy.COUNTRY, FILTER, TODAY)).thenReturn(List.of(
                new GroupRow("DE", "Germany", 2, new BigDecimal("100000.00"), new BigDecimal("100000.00"))));

        List<GroupStat> groups = service().byGroup("COUNTRY", FILTER);

        assertThat(groups).containsExactly(
                new GroupStat("DE", "Germany", 2, new BigDecimal("100000.00"), new BigDecimal("100000.00")));
    }

    @Test
    @DisplayName("FR-4.2: groupBy is accepted in any case with surrounding whitespace")
    void byGroup_isCaseAndWhitespaceTolerant() {
        when(repository.byGroup(GroupBy.JOB_LEVEL, FILTER, TODAY)).thenReturn(List.of(
                new GroupRow("L4", "L4", 1, BigDecimal.TEN, BigDecimal.TEN)));

        assertThat(service().byGroup(" job_level ", FILTER)).hasSize(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "TEAM", "country;DROP TABLE employee", "DEPARTMENTS"})
    @DisplayName("FR-4.2: a missing or unknown groupBy is a 400 with code INVALID_GROUP_BY that never echoes the value")
    void byGroup_invalidValue_isRejected(String groupBy) {
        assertThatThrownBy(() -> service().byGroup(groupBy, FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_GROUP_BY");
                    assertThat(e.getField()).isEqualTo("groupBy");
                    assertThat(e.getMessage()).contains("DEPARTMENT", "COUNTRY", "JOB_LEVEL");
                    if (groupBy != null && !groupBy.isBlank()) {
                        assertThat(e.getMessage()).doesNotContain(groupBy);
                    }
                });
    }

    // ---- distribution ----------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.3: without a buckets value the histogram has 12 buckets")
    void distribution_defaultsToTwelveBuckets() {
        when(repository.distribution(FILTER, TODAY, 12)).thenReturn(List.of(
                new BucketRow(new BigDecimal("60000.00"), new BigDecimal("140000.00"), 4)));

        List<DistributionBucket> buckets = service().distribution(null, FILTER);

        assertThat(buckets).containsExactly(
                new DistributionBucket(new BigDecimal("60000.00"), new BigDecimal("140000.00"), 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "7", "50", " 5 "})
    @DisplayName("FR-4.3: buckets between 2 and 50 are passed to the query as asked")
    void distribution_validBuckets_arePassedThrough(String buckets) {
        int expected = Integer.parseInt(buckets.trim());
        when(repository.distribution(FILTER, TODAY, expected))
                .thenReturn(List.of(new BucketRow(BigDecimal.ONE, BigDecimal.TEN, expected)));

        assertThat(service().distribution(buckets, FILTER)).extracting(DistributionBucket::count)
                .containsExactly((long) expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "1", "0", "-3", "51", "1000", "abc", "1.5", "99999999999"})
    @DisplayName("FR-4.3: buckets outside 2..50 or not a whole number is a 400 with code INVALID_BUCKETS that never echoes the value")
    void distribution_invalidBuckets_isRejected(String buckets) {
        assertThatThrownBy(() -> service().distribution(buckets, FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_BUCKETS");
                    assertThat(e.getField()).isEqualTo("buckets");
                    assertThat(e.getMessage()).contains("2").contains("50");
                    // A one-character value such as "0" is trivially a substring of the message's "50".
                    if (buckets.strip().length() > 1) {
                        assertThat(e.getMessage()).doesNotContain(buckets);
                    }
                });
    }
}
