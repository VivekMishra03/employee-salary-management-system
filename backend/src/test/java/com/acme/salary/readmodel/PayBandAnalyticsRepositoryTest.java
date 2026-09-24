package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.PayBandAdherence;
import com.acme.salary.support.SqlFixture;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-4.4 / FR-4.7: pay-band adherence SQL on a real PostgreSQL, over a fixture whose expected values
 * are worked out by hand. asOf is 2026-06-15. Amounts are the LOCAL annualised amount; bands are local.
 *
 * <pre>
 * Bands in force on 2026-06-15:
 *   role1 Software Engineer x US : 80,000 / 100,000 / 120,000 (USD, from 2025-01-01)
 *                                  (an older 50/60/70k band ended 2025-01-01 and must be ignored)
 *   role1 Software Engineer x DE : 60,000 /  75,000 /  90,000 (EUR, from 2025-01-01)   same role, other market
 *   role3 Analyst x US           : 40,000 /  50,000 /  60,000 (USD, from 2026-06-15: starts exactly on asOf)
 *   role4 Intern x US            :      0 /       0 /  50,000 (mid = 0: allowed by the CHECK, must not divide by zero)
 *   role5 Manager x US           : two bands both in force (10/20/30k from 2024-01-01, 40/50/60k from 2026-01-01)
 *   role2 Sales Rep x US         : band from 2027-01-01 (not yet effective)      -> no band today
 *   role2 Sales Rep x DE         : band 30/35/40k EUR that ENDED on 2026-06-15   -> no band today (to is exclusive)
 *
 *  id  name         role/loc   amount     expected
 *  1   Ann Below    1/US        79,999.99 BELOW   compa 79999.99/100000 = 0.7999999 -> 0.80
 *  2   Bob AtMin    1/US        80,000    WITHIN  (min inclusive)            0.80
 *  3   Cy Mid       1/US       100,000    WITHIN                             1.00
 *  4   Di AtMax     1/US       120,000    WITHIN  (max inclusive)            1.20
 *  5   Ed Above     1/US       120,000.01 ABOVE   1.2000001 -> 1.20
 *  6   Fay German   1/DE        70,000    WITHIN  under the DE band (would be BELOW under the US one)  70/75 = 0.9333 -> 0.93
 *  7   Gus NoBand   2/US        50,000    NO_BAND (band not yet effective)
 *  8   Hal Expired  2/DE        36,000    NO_BAND (band ended on asOf)
 *  9   Ivy Starts   3/US        45,000    WITHIN  (band starts on asOf)      45/50 = 0.90
 *  10  Jo Gone      1/US        50,000    TERMINATED: excluded by default
 *  11  Kim Half     1/US       100,500    WITHIN  1.005 exactly -> HALF_UP 1.01 (HALF_EVEN would give 1.00)
 *  12  Lou ZeroMid  4/US        10,000    WITHIN  compa null (mid = 0)
 *  13  Meg Overlap  5/US        55,000    WITHIN  under the LATER band (mid 50k): 1.10; under the older it would be ABOVE
 * </pre>
 *
 * <p>Default population: 12 employees. below 1 (Ann), within 8 (2,3,4,6,9,11,12,13), above 1 (Ed),
 * no band 2 (Gus, Hal). Sort order (compa ascending, nulls last, then code):
 * 1(0.80) 2(0.80) 9(0.90) 6(0.93) 3(1.00) 11(1.01) 13(1.10) 4(1.20) 5(1.20) 7 8 12.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(PayBandAnalyticsRepository.class)
class PayBandAnalyticsRepositoryTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter ALL = new EmployeeFilter(null, null, null, null, null, null);
    private static final int NO_LIMIT = 100;

    @Autowired
    private PayBandAnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SqlFixture f = new SqlFixture(jdbc).baseData()
                .jobRole(1, "Software Engineer", "L4").jobRole(2, "Sales Rep", "L3").jobRole(3, "Analyst", "L2")
                .jobRole(4, "Intern", "L1").jobRole(5, "Manager", "L6");

        f.payBand(1, 1, 1, "USD", "50000.00", "60000.00", "70000.00", "2020-01-01", "2025-01-01");
        f.payBand(2, 1, 1, "USD", "80000.00", "100000.00", "120000.00", "2025-01-01", null);
        f.payBand(3, 1, 2, "EUR", "60000.00", "75000.00", "90000.00", "2025-01-01", null);
        f.payBand(4, 3, 1, "USD", "40000.00", "50000.00", "60000.00", "2026-06-15", null);
        f.payBand(5, 4, 1, "USD", "0.00", "0.00", "50000.00", "2020-01-01", null);
        f.payBand(6, 5, 1, "USD", "10000.00", "20000.00", "30000.00", "2024-01-01", null);
        f.payBand(7, 5, 1, "USD", "40000.00", "50000.00", "60000.00", "2026-01-01", null);
        f.payBand(8, 2, 1, "USD", "40000.00", "45000.00", "50000.00", "2027-01-01", null);
        f.payBand(9, 2, 2, "EUR", "30000.00", "35000.00", "40000.00", "2020-01-01", "2026-06-15");

        person(f, 1, "Ann", "Below", 1, 1, "USD", "79999.99");
        person(f, 2, "Bob", "AtMin", 1, 1, "USD", "80000.00");
        person(f, 3, "Cy", "Mid", 1, 1, "USD", "100000.00");
        person(f, 4, "Di", "AtMax", 1, 1, "USD", "120000.00");
        person(f, 5, "Ed", "Above", 1, 1, "USD", "120000.01");
        person(f, 6, "Fay", "German", 1, 2, "EUR", "70000.00");
        person(f, 7, "Gus", "NoBand", 2, 1, "USD", "50000.00");
        person(f, 8, "Hal", "Expired", 2, 2, "EUR", "36000.00");
        person(f, 9, "Ivy", "Starts", 3, 1, "USD", "45000.00");
        f.employee(10, "Jo", "Gone", null, "TERMINATED", "1.000", 1, 1, 1, "2020-01-01", "2025-12-31");
        f.salary(10, 10, "2020-01-01", null, "USD", "50000.00", "50000.00", "NEW_HIRE");
        person(f, 11, "Kim", "Half", 1, 1, "USD", "100500.00");
        person(f, 12, "Lou", "ZeroMid", 4, 1, "USD", "10000.00");
        person(f, 13, "Meg", "Overlap", 5, 1, "USD", "55000.00");
    }

    private static void person(SqlFixture f, long id, String first, String last, long role, long location,
                               String currency, String amount) {
        f.employee(id, first, last, null, 1, role, location);
        f.salary(id, id, "2020-01-01", null, currency, amount, amount, "NEW_HIRE");
    }

    private List<PayBandRow> rows(EmployeeFilter filter, PayBandAdherence adherence, int limit, long offset) {
        return repository.rows(filter, AS_OF, adherence, limit, offset);
    }

    private static List<Long> ids(List<PayBandRow> rows) {
        return rows.stream().map(PayBandRow::employeeId).toList();
    }

    private static PayBandRow rowOf(List<PayBandRow> rows, long employeeId) {
        return rows.stream().filter(r -> r.employeeId() == employeeId).findFirst().orElseThrow();
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).as("money value").isNotNull();
        assertThat(actual.toPlainString()).isEqualTo(expected);
    }

    // ---- counts ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: below / within / above / no-band counts over the default population are 1 / 8 / 1 / 2")
    void counts_defaultPopulation_matchHandComputedFigures() {
        PayBandCountsRow counts = repository.counts(ALL, AS_OF);

        assertThat(counts).isEqualTo(new PayBandCountsRow(1, 8, 1, 2));
    }

    @Test
    @DisplayName("FR-4.7: filters narrow the counts (country DE: Fay within, Hal without a band)")
    void counts_respectFilters() {
        PayBandCountsRow counts = repository.counts(
                new EmployeeFilter(null, null, "de", null, null, null), AS_OF);

        assertThat(counts).isEqualTo(new PayBandCountsRow(0, 1, 0, 1));
    }

    @Test
    @DisplayName("FR-4.4: an explicit TERMINATED status includes the leaver, who is below the band (50k < 80k)")
    void counts_terminatedStatus_includesLeaver() {
        PayBandCountsRow counts = repository.counts(
                new EmployeeFilter(null, null, null, EmploymentStatus.TERMINATED, null, null), AS_OF);

        assertThat(counts).isEqualTo(new PayBandCountsRow(1, 0, 0, 0));
    }

    @Test
    @DisplayName("FR-4.4: an empty slice has four zero counts, not an error")
    void counts_emptySlice_areZero() {
        PayBandCountsRow counts = repository.counts(
                new EmployeeFilter("nobody-has-this-name", null, null, null, null, null), AS_OF);

        assertThat(counts).isEqualTo(new PayBandCountsRow(0, 0, 0, 0));
    }

    // ---- currency ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: a salary in another currency than the band (moved country, old GBP pay) is NO_BAND, never compared")
    void band_inADifferentCurrencyThanTheSalary_isNoBand() {
        // Works in Berlin (role 1 x DE band: 60/75/90k EUR) but still paid 60,000 GBP from before the move.
        // Compared naively, 50,000 (GBP) is below 60,000 (EUR): a currency mix-up, not a fact about pay.
        SqlFixture f = new SqlFixture(jdbc);
        person(f, 20, "Gwen", "Moved", 1, 2, "GBP", "50000.00");
        person(f, 21, "Gil", "Matching", 1, 2, "EUR", "70000.00");
        EmployeeFilter moved = new EmployeeFilter("moved", null, null, null, null, null);

        PayBandRow gwen = rowOf(rows(moved, null, NO_LIMIT, 0), 20);

        assertThat(gwen.adherence()).isEqualTo(PayBandAdherence.NO_BAND);
        assertThat(gwen.compaRatio()).isNull();
        assertThat(gwen.bandMin()).isNull();
        assertThat(gwen.currencyCode()).isEqualTo("GBP");
        assertThat(repository.counts(moved, AS_OF)).isEqualTo(new PayBandCountsRow(0, 0, 0, 1));
        // Same role and market, salary in the band's own currency: classified normally (70/75 = 0.93).
        EmployeeFilter matching = new EmployeeFilter("matching", null, null, null, null, null);
        PayBandRow gil = rowOf(rows(matching, null, NO_LIMIT, 0), 21);
        assertThat(gil.adherence()).isEqualTo(PayBandAdherence.WITHIN);
        assertMoney(gil.compaRatio(), "0.93");
        assertThat(repository.counts(matching, AS_OF)).isEqualTo(new PayBandCountsRow(0, 1, 0, 0));
    }

    // ---- adherence classification ----------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: one cent below the minimum is BELOW; exactly the minimum is WITHIN")
    void adherence_minimumBoundaryIsInclusive() {
        List<PayBandRow> all = rows(ALL, null, NO_LIMIT, 0);

        assertThat(rowOf(all, 1).adherence()).isEqualTo(PayBandAdherence.BELOW);
        assertThat(rowOf(all, 2).adherence()).isEqualTo(PayBandAdherence.WITHIN);
    }

    @Test
    @DisplayName("FR-4.4: exactly the maximum is WITHIN; one cent above the maximum is ABOVE")
    void adherence_maximumBoundaryIsInclusive() {
        List<PayBandRow> all = rows(ALL, null, NO_LIMIT, 0);

        assertThat(rowOf(all, 4).adherence()).isEqualTo(PayBandAdherence.WITHIN);
        assertThat(rowOf(all, 5).adherence()).isEqualTo(PayBandAdherence.ABOVE);
    }

    @Test
    @DisplayName("FR-4.4: the band is chosen by role AND location: 70,000 EUR is within the German band")
    void band_isChosenByRoleAndLocation() {
        PayBandRow fay = rowOf(rows(ALL, null, NO_LIMIT, 0), 6);

        assertThat(fay.adherence()).isEqualTo(PayBandAdherence.WITHIN);
        assertMoney(fay.bandMin(), "60000.00");
        assertMoney(fay.bandMid(), "75000.00");
        assertMoney(fay.bandMax(), "90000.00");
        assertMoney(fay.compaRatio(), "0.93");
        assertThat(fay.currencyCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("FR-4.4: a band that has not started yet, or ended on asOf, is not in force: NO_BAND with null band fields")
    void band_notYetEffectiveOrExpired_isNoBand() {
        List<PayBandRow> all = rows(ALL, null, NO_LIMIT, 0);

        for (long id : List.of(7L, 8L)) {
            PayBandRow row = rowOf(all, id);
            assertThat(row.adherence()).isEqualTo(PayBandAdherence.NO_BAND);
            assertThat(row.bandMin()).isNull();
            assertThat(row.bandMid()).isNull();
            assertThat(row.bandMax()).isNull();
            assertThat(row.compaRatio()).isNull();
        }
    }

    @Test
    @DisplayName("FR-4.4: a band starting exactly on asOf is in force (Ivy 45,000 in 40k/50k/60k -> 0.90)")
    void band_startingOnAsOf_isInForce() {
        PayBandRow ivy = rowOf(rows(ALL, null, NO_LIMIT, 0), 9);

        assertThat(ivy.adherence()).isEqualTo(PayBandAdherence.WITHIN);
        assertMoney(ivy.compaRatio(), "0.90");
    }

    @Test
    @DisplayName("FR-4.4: one day earlier the same band has not started (Ivy NO_BAND) and the expiring band is still in force (Hal WITHIN)")
    void band_dayBeforeAsOf_flipsBothBoundaries() {
        List<PayBandRow> dayBefore = repository.rows(ALL, LocalDate.of(2026, 6, 14), null, NO_LIMIT, 0);

        assertThat(rowOf(dayBefore, 9).adherence()).isEqualTo(PayBandAdherence.NO_BAND);
        // Hal 36,000 EUR in the 30k/35k/40k band that ends on 2026-06-15 (exclusive): 36/35 = 1.0286 -> 1.03.
        PayBandRow hal = rowOf(dayBefore, 8);
        assertThat(hal.adherence()).isEqualTo(PayBandAdherence.WITHIN);
        assertMoney(hal.compaRatio(), "1.03");
    }

    @Test
    @DisplayName("FR-4.4: two bands in force at once do not duplicate the employee; the later effective_from wins")
    void overlappingBands_useTheLatestAndKeepOneRowPerEmployee() {
        List<PayBandRow> all = rows(ALL, null, NO_LIMIT, 0);

        assertThat(all.stream().filter(r -> r.employeeId() == 13).count()).isEqualTo(1);
        PayBandRow meg = rowOf(all, 13);
        assertMoney(meg.bandMid(), "50000.00");
        assertMoney(meg.compaRatio(), "1.10");
        assertThat(meg.adherence()).isEqualTo(PayBandAdherence.WITHIN);
    }

    @Test
    @DisplayName("FR-4.4: the expired 50k/60k/70k band is never used for a current employee")
    void expiredBand_isIgnored() {
        // Cy earns 100,000. Against the expired band (mid 60,000) the ratio would be 1.67 and ABOVE.
        PayBandRow cy = rowOf(rows(ALL, null, NO_LIMIT, 0), 3);

        assertMoney(cy.compaRatio(), "1.00");
        assertThat(cy.adherence()).isEqualTo(PayBandAdherence.WITHIN);
    }

    // ---- compa-ratio -----------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: compa-ratio is rounded HALF_UP to 2 places: 100,500 / 100,000 = 1.005 -> 1.01")
    void compaRatio_roundsHalfUp() {
        assertMoney(rowOf(rows(ALL, null, NO_LIMIT, 0), 11).compaRatio(), "1.01");
    }

    @Test
    @DisplayName("FR-4.4: compa-ratio is amount / mid: 79,999.99 / 100,000 rounds to 0.80")
    void compaRatio_belowBand() {
        assertMoney(rowOf(rows(ALL, null, NO_LIMIT, 0), 1).compaRatio(), "0.80");
    }

    @Test
    @DisplayName("FR-4.4: a band with mid = 0 gives a null compa-ratio, not a division error, and still classifies")
    void compaRatio_zeroMid_isNullAndAdherenceStillComputed() {
        PayBandRow lou = rowOf(rows(ALL, null, NO_LIMIT, 0), 12);

        assertThat(lou.compaRatio()).isNull();
        assertThat(lou.adherence()).isEqualTo(PayBandAdherence.WITHIN);
        assertMoney(lou.bandMax(), "50000.00");
    }

    @Test
    @DisplayName("FR-4.4: the row carries identity, role, country, currency and the local amount")
    void row_carriesTheDescriptiveFields() {
        PayBandRow ann = rowOf(rows(ALL, null, NO_LIMIT, 0), 1);

        assertThat(ann.employeeCode()).isEqualTo("ACME-000001");
        assertThat(ann.fullName()).isEqualTo("Ann Below");
        assertThat(ann.jobTitle()).isEqualTo("Software Engineer");
        assertThat(ann.jobLevel()).isEqualTo("L4");
        assertThat(ann.countryCode()).isEqualTo("US");
        assertThat(ann.currencyCode()).isEqualTo("USD");
        assertMoney(ann.annualisedAmount(), "79999.99");
        assertMoney(ann.bandMin(), "80000.00");
        assertMoney(ann.bandMid(), "100000.00");
        assertMoney(ann.bandMax(), "120000.00");
    }

    // ---- population, ordering, paging ------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: terminated employees are not listed by default (Jo Gone, id 10)")
    void rows_excludeTerminatedByDefault() {
        assertThat(ids(rows(ALL, null, NO_LIMIT, 0))).doesNotContain(10L).hasSize(12);
    }

    @Test
    @DisplayName("FR-4.4: rows are sorted by compa-ratio ascending, ties by employee code, no-band rows last")
    void rows_areSortedByCompaRatioThenCode() {
        assertThat(ids(rows(ALL, null, NO_LIMIT, 0)))
                .containsExactly(1L, 2L, 9L, 6L, 3L, 11L, 13L, 4L, 5L, 7L, 8L, 12L);
    }

    @Test
    @DisplayName("FR-4.4: the second page of 5 holds the sixth to tenth rows of the sorted list")
    void rows_pageTwo_isTheNextSlice() {
        assertThat(ids(rows(ALL, null, 5, 5))).containsExactly(11L, 13L, 4L, 5L, 7L);
    }

    @Test
    @DisplayName("FR-4.4: the last page is short, and a page past the end is empty")
    void rows_lastAndBeyondLastPage() {
        assertThat(ids(rows(ALL, null, 5, 10))).containsExactly(8L, 12L);
        assertThat(rows(ALL, null, 5, 15)).isEmpty();
    }

    @Test
    @DisplayName("FR-4.4: counts do not depend on paging: they still cover all 12 after a small page is read")
    void counts_areIndependentOfPaging() {
        rows(ALL, null, 3, 0);

        assertThat(repository.counts(ALL, AS_OF)).isEqualTo(new PayBandCountsRow(1, 8, 1, 2));
    }

    // ---- adherence filter ------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: filtering to ABOVE lists only Ed")
    void adherenceFilter_above() {
        assertThat(ids(rows(ALL, PayBandAdherence.ABOVE, NO_LIMIT, 0))).containsExactly(5L);
    }

    @Test
    @DisplayName("FR-4.4: filtering to NO_BAND lists Gus and Hal in code order (Lou has a band, only no usable mid)")
    void adherenceFilter_noBand() {
        assertThat(ids(rows(ALL, PayBandAdherence.NO_BAND, NO_LIMIT, 0))).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("FR-4.4: filtering to WITHIN keeps the sort order and includes the null-compa row last")
    void adherenceFilter_within_isSorted() {
        assertThat(ids(rows(ALL, PayBandAdherence.WITHIN, NO_LIMIT, 0)))
                .containsExactly(2L, 9L, 6L, 3L, 11L, 13L, 4L, 12L);
    }

    @Test
    @DisplayName("FR-4.4 / FR-4.7: an adherence filter combines with the other filters")
    void adherenceFilter_combinesWithCountryFilter() {
        assertThat(ids(repository.rows(new EmployeeFilter(null, null, "DE", null, null, null), AS_OF,
                PayBandAdherence.WITHIN, NO_LIMIT, 0))).containsExactly(6L);
    }
}
