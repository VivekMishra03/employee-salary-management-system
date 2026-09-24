package com.acme.salary.service;

import com.acme.salary.config.AnalyticsProperties;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.GenderGapStat;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.readmodel.GenderGapAnalyticsRepository;
import com.acme.salary.readmodel.GenderGapRow;
import com.acme.salary.readmodel.GroupBy;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FR-4.5 / FR-4.7: the small-group suppression rule and the request validation, without a database.
 * The rule: a group's gap is reported only when BOTH the male and the female count reach the
 * configured minimum; otherwise the gap AND the counts are withheld (a small count next to a group
 * label is itself identifying).
 */
@ExtendWith(MockitoExtension.class)
class GenderGapServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T23:59:59Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter FILTER = new EmployeeFilter(null, 3L, null, null, null, null);

    @Mock
    private GenderGapAnalyticsRepository repository;

    private GenderGapService service(int minGroupSize) {
        return new GenderGapService(repository, new AnalyticsProperties(minGroupSize), CLOCK);
    }

    private static GenderGapRow row(String label, long male, long female, String meanGap, String medianGap) {
        return new GenderGapRow("k-" + label, label, male, female, meanGap == null ? null : new BigDecimal(meanGap),
                medianGap == null ? null : new BigDecimal(medianGap));
    }

    private List<GenderGapStat> gaps(int min, GenderGapRow... rows) {
        when(repository.gaps(GroupBy.DEPARTMENT, FILTER, TODAY)).thenReturn(List.of(rows));
        return service(min).gaps("DEPARTMENT", FILTER);
    }

    @Test
    @DisplayName("FR-4.5: at 5 men and 5 women (the minimum) the gap and the counts are reported")
    void group_atTheMinimumOnBothSides_isShown() {
        GenderGapStat stat = gaps(5, row("Eng", 5, 5, "12.34", "-3.20")).get(0);

        assertThat(stat.suppressed()).isFalse();
        assertThat(stat.maleCount()).isEqualTo(5L);
        assertThat(stat.femaleCount()).isEqualTo(5L);
        assertThat(stat.meanGapPct()).isEqualByComparingTo("12.34");
        assertThat(stat.medianGapPct()).isEqualByComparingTo("-3.20");
        assertThat(stat.key()).isEqualTo("k-Eng");
        assertThat(stat.label()).isEqualTo("Eng");
    }

    @Test
    @DisplayName("FR-4.5: with 4 women against 5 men the group is suppressed: no gap, no counts")
    void group_oneSideBelowTheMinimum_isSuppressed() {
        GenderGapStat stat = gaps(5, row("Eng", 5, 4, "12.34", "11.11")).get(0);

        assertThat(stat.suppressed()).isTrue();
        assertThat(stat.meanGapPct()).isNull();
        assertThat(stat.medianGapPct()).isNull();
        assertThat(stat.maleCount()).isNull();
        assertThat(stat.femaleCount()).isNull();
    }

    @Test
    @DisplayName("FR-4.5: with 4 men against 5 women the group is suppressed too: both sides must reach the minimum")
    void group_maleSideBelowTheMinimum_isSuppressed() {
        assertThat(gaps(5, row("Eng", 4, 5, "12.34", "11.11")).get(0).suppressed()).isTrue();
    }

    @Test
    @DisplayName("FR-4.5: a group with no women at all is suppressed")
    void group_withNoWomen_isSuppressed() {
        GenderGapStat stat = gaps(5, row("Eng", 9, 0, null, null)).get(0);

        assertThat(stat.suppressed()).isTrue();
        assertThat(stat.femaleCount()).isNull();
    }

    @Test
    @DisplayName("FR-4.5: the minimum is configurable: at 2, a group of 2 and 2 is shown but 1 and 3 is not")
    void minimum_comesFromTheConfiguration() {
        List<GenderGapStat> stats = gaps(2, row("A", 2, 2, "1.00", "2.00"), row("B", 1, 3, "1.00", "2.00"));

        assertThat(stats).extracting(GenderGapStat::suppressed).containsExactly(false, true);
    }

    @Test
    @DisplayName("FR-4.5: an undefined gap on a large enough group (male mean zero) is not suppressed, just null")
    void group_bigEnoughButGapUndefined_isShownWithNullGap() {
        GenderGapStat stat = gaps(5, row("Eng", 6, 6, null, null)).get(0);

        assertThat(stat.suppressed()).isFalse();
        assertThat(stat.meanGapPct()).isNull();
        assertThat(stat.maleCount()).isEqualTo(6L);
    }

    @Test
    @DisplayName("FR-4.5: the order of the groups from the read model is kept")
    void groups_keepTheReadModelOrder() {
        List<GenderGapStat> stats = gaps(5, row("Alpha", 5, 5, "1.00", "1.00"), row("Beta", 1, 1, null, null),
                row("Gamma", 7, 8, "2.00", "2.00"));

        assertThat(stats).extracting(GenderGapStat::label).containsExactly("Alpha", "Beta", "Gamma");
    }

    @Test
    @DisplayName("FR-4.5: JOB_LEVEL is a supported grouping, case-insensitively")
    void groupBy_jobLevel_isSupported() {
        when(repository.gaps(GroupBy.JOB_LEVEL, FILTER, TODAY)).thenReturn(List.of());

        assertThat(service(5).gaps("job_level", FILTER)).isEmpty();
    }

    @Test
    @DisplayName("FR-4.5: COUNTRY is not allowed: 400 with the stable code UNSUPPORTED_GROUP_BY")
    void groupBy_country_isRejected() {
        assertThatThrownBy(() -> service(5).gaps("COUNTRY", FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("UNSUPPORTED_GROUP_BY");
                    assertThat(e.getField()).isEqualTo("groupBy");
                });
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "groupBy \"{0}\" is rejected")
    @NullAndEmptySource
    @ValueSource(strings = {"SHOE_SIZE", " "})
    @DisplayName("FR-4.5: a missing or unknown groupBy is INVALID_GROUP_BY, without echoing the value")
    void groupBy_invalid_isRejected(String groupBy) {
        assertThatThrownBy(() -> service(5).gaps(groupBy, FILTER))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_GROUP_BY");
                    assertThat(e.getField()).isEqualTo("groupBy");
                    assertThat(e.getMessage()).doesNotContain("SHOE_SIZE");
                });
        verifyNoInteractions(repository);
    }
}
