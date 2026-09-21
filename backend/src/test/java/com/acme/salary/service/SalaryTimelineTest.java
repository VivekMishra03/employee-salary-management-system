package com.acme.salary.service;

import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.service.SalaryTimeline.Interval;
import com.acme.salary.service.SalaryTimeline.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-3.2 / FR-3.6: the temporal transition rules, as a pure unit test. Given an employee's existing
 * salary intervals and a new effective date, {@link SalaryTimeline#plan} decides which record to
 * close and where the new one ends -- with no database involved, so every edge is provable here.
 *
 * <p>Intervals are half-open {@code [from, to)}; a null {@code to} means "still in force". The
 * fixture employee was hired 2020-01-01.
 */
class SalaryTimelineTest {

    private static final LocalDate HIRE = LocalDate.of(2020, 1, 1);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Interval iv(long id, String from, String to) {
        return new Interval(id, d(from), to == null ? null : d(to));
    }

    private static Plan plan(List<Interval> history, String effectiveFrom) {
        return SalaryTimeline.plan(HIRE, null, history, d(effectiveFrom));
    }

    private static String code(Throwable t) {
        return ((RequestValidationException) t).getCode();
    }

    // ---- the first record ------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.2: the first record starts on the hire date, closes nothing, and is open-ended")
    void firstRecord_onTheHireDate_isOpenEnded() {
        Plan plan = plan(List.of(), "2020-01-01");

        assertThat(plan.recordToCloseId()).isNull();
        assertThat(plan.newEffectiveTo()).isNull();
    }

    @Test
    @DisplayName("FR-3.2: a first record starting after the hire date would leave a gap, so it is rejected")
    void firstRecord_afterTheHireDate_isRejectedAsAGap() {
        assertThatThrownBy(() -> plan(List.of(), "2020-03-01"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("GAP_AFTER_HIRE"));
    }

    // ---- bounds ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.2: a record cannot take effect before the employee was hired")
    void effectiveBeforeHire_isRejected() {
        assertThatThrownBy(() -> plan(List.of(iv(1, "2020-01-01", null)), "2019-12-31"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("EFFECTIVE_BEFORE_HIRE"));
    }

    @Test
    @DisplayName("a record cannot take effect after the employee was terminated")
    void effectiveAfterTermination_isRejected() {
        assertThatThrownBy(() -> SalaryTimeline.plan(HIRE, d("2024-06-30"),
                List.of(iv(1, "2020-01-01", null)), d("2024-07-01")))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("EFFECTIVE_AFTER_TERMINATION"));
    }

    @Test
    @DisplayName("a record may take effect on the termination date itself")
    void effectiveOnTheTerminationDate_isAllowed() {
        Plan plan = SalaryTimeline.plan(HIRE, d("2024-06-30"), List.of(iv(1, "2020-01-01", null)), d("2024-06-30"));

        assertThat(plan.recordToCloseId()).isEqualTo(1L);
    }

    // ---- a raise ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.2: a raise closes the open record and the new record is open-ended")
    void raise_closesTheOpenRecord() {
        Plan plan = plan(List.of(iv(1, "2020-01-01", null)), "2021-06-01");

        assertThat(plan.recordToCloseId()).isEqualTo(1L);
        assertThat(plan.newEffectiveTo()).isNull();
    }

    @Test
    @DisplayName("FR-3.2: with several records, only the one in force on the effective date is closed")
    void raise_closesOnlyTheRecordInForce() {
        List<Interval> history = List.of(iv(1, "2020-01-01", "2021-01-01"), iv(2, "2021-01-01", null));

        Plan plan = plan(history, "2022-05-05");

        assertThat(plan.recordToCloseId()).isEqualTo(2L);
    }

    // ---- back-dated ------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.6: a back-dated change is slotted in and ends where the next record begins, leaving no gap")
    void backDated_insertsIntoTheMiddleWithoutAGap() {
        List<Interval> history = List.of(iv(1, "2020-01-01", "2022-01-01"), iv(2, "2022-01-01", null));

        Plan plan = plan(history, "2021-06-01");

        assertThat(plan.recordToCloseId()).isEqualTo(1L);
        // Record 1 is cut to end 2021-06-01; the new record fills [2021-06-01, 2022-01-01).
        assertThat(plan.newEffectiveTo()).isEqualTo(d("2022-01-01"));
    }

    @Test
    @DisplayName("FR-3.6: back-dating onto the most recent record keeps the new record open-ended")
    void backDated_withinTheOpenRecord_staysOpenEnded() {
        Plan plan = plan(List.of(iv(1, "2020-01-01", null)), "2020-06-01");

        assertThat(plan.recordToCloseId()).isEqualTo(1L);
        assertThat(plan.newEffectiveTo()).isNull();
    }

    // ---- same-day / corrections ------------------------------------------------------------

    @Test
    @DisplayName("assumption 4: a record starting the same day supersedes the old one, which is cut to zero length, not deleted")
    void sameStartDate_supersedesTheExistingRecord() {
        List<Interval> history = List.of(iv(1, "2020-01-01", "2022-01-01"), iv(2, "2022-01-01", null));

        Plan plan = plan(history, "2022-01-01");

        assertThat(plan.recordToCloseId()).isEqualTo(2L);
        assertThat(plan.newEffectiveTo()).isNull();
    }

    @Test
    @DisplayName("a zero-length record left by an earlier correction never counts as the record in force")
    void zeroLengthRecord_isIgnored() {
        // 2 is a superseded, zero-length record; 3 is the live record starting the same day.
        List<Interval> history = List.of(iv(1, "2020-01-01", "2022-01-01"), iv(2, "2022-01-01", "2022-01-01"),
                iv(3, "2022-01-01", null));

        Plan plan = plan(history, "2023-01-01");

        assertThat(plan.recordToCloseId()).isEqualTo(3L);
    }

    // ---- future-dated ----------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.6: a future-dated change closes the open record at the future date")
    void futureDated_closesTheOpenRecordAtThatDate() {
        Plan plan = plan(List.of(iv(1, "2020-01-01", null)), "2030-01-01");

        assertThat(plan.recordToCloseId()).isEqualTo(1L);
        assertThat(plan.newEffectiveTo()).isNull();
    }

    // ---- integrity of the existing history -------------------------------------------------

    @Test
    @DisplayName("FR-3.2: if existing history stops short of the new date, the change is rejected rather than leaving a gap")
    void dateAfterAClosedFinalRecord_isRejectedAsAGap() {
        assertThatThrownBy(() -> plan(List.of(iv(1, "2020-01-01", "2021-01-01")), "2022-01-01"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("TIMELINE_GAP"));
    }

    @Test
    @DisplayName("the order the history is supplied in does not change the outcome")
    void plan_isIndependentOfInputOrder() {
        List<Interval> ascending = List.of(iv(1, "2020-01-01", "2021-01-01"), iv(2, "2021-01-01", null));
        List<Interval> descending = List.of(iv(2, "2021-01-01", null), iv(1, "2020-01-01", "2021-01-01"));

        assertThat(plan(descending, "2020-06-01")).isEqualTo(plan(ascending, "2020-06-01"));
    }
}
