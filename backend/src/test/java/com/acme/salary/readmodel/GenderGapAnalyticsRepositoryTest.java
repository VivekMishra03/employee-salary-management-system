package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.EmploymentStatus;
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
 * FR-4.5 / FR-4.7: the gender-gap SQL on a real PostgreSQL. The read model returns the raw gap for
 * every group that has a MALE or FEMALE employee; suppression of small groups is the service's rule
 * and is tested there. Amounts are BASE-currency annualised (asOf 2026-06-15). Gap = (M - F) / M * 100.
 *
 * <pre>
 *  Dept Engineering(1)  roles: L4 = role1, L5 = role2
 *    M  100,000 (L4)  M 120,000 (L4)  M 200,000 (L5)
 *    F   90,000 (L4)  F 100,000 base = EUR 92,000 local (L5)          <- base currency is what counts
 *    NON_BINARY, PREFER_NOT_TO_SAY, null gender at 1,000,000 each     <- must not enter any figure
 *    TERMINATED male 999,999 (L4)                                     <- excluded by default
 *    mean M 140,000  mean F 95,000  -> (140-95)/140 = 32.142857 -> 32.14
 *    median M 120,000 median F 95,000 -> 25/120 = 20.8333 -> 20.83
 *  Dept Sales(2), all L5:  M 50,000, 70,000 ; F 80,000, 90,000, 100,000
 *    mean M 60,000 mean F 90,000 -> -30/60 = -50.00 (men paid less: negative) ; medians 60k / 90k -> -50.00
 *  Dept Operations(3), L3: M 100,000 x3 ; F 87,655
 *    (100,000 - 87,655) / 100,000 = 12.345 exactly -> HALF_UP 12.35 (HALF_EVEN would give 12.34)
 *  Dept Zero(4), L2:       M 0 ; F 50,000     mean M = 0 -> the gap is undefined: null, not a division error
 *  Dept Solo(5), L1:       M 70,000 ; M 90,000  (no women) -> null gaps, counts 2 / 0
 *  Dept Nonbinary(6), L7:  NON_BINARY 60,000 only -> no MALE/FEMALE at all: the group is not returned
 *
 *  By level: L4 = M {100k,120k} F {90k}: 20,000/110,000 = 18.18 (both mean and median)
 *            L5 = M {200k,50k,70k} F {100k,80k,90k,100k}: mean M 106,666.67 mean F 92,500 -> 13.28125 -> 13.28 ;
 *                 median M 70,000 median F 95,000 -> -25/70 = -35.714 -> -35.71
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(GenderGapAnalyticsRepository.class)
class GenderGapAnalyticsRepositoryTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter ALL = new EmployeeFilter(null, null, null, null, null, null);

    @Autowired
    private GenderGapAnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SqlFixture f = new SqlFixture(jdbc).baseData();
        jdbc.update("INSERT INTO department (id, code, name) VALUES (3, 'OPS', 'Operations'), (4, 'ZER', 'Zero'), "
                + "(5, 'SOL', 'Solo'), (6, 'NBD', 'Nonbinary')");
        f.jobRole(1, "Engineer", "L4").jobRole(2, "Senior Engineer", "L5").jobRole(3, "Ops Agent", "L3")
                .jobRole(4, "Zero Role", "L2").jobRole(5, "Solo Role", "L1").jobRole(6, "Nonbinary Role", "L7");

        // Engineering
        person(f, 1, "MALE", 1, 1, 1, "100000.00");
        person(f, 2, "MALE", 1, 1, 1, "120000.00");
        person(f, 3, "MALE", 1, 2, 1, "200000.00");
        person(f, 4, "FEMALE", 1, 1, 1, "90000.00");
        f.employee(5, "P5", "Person", "FEMALE", 1, 2, 2);
        f.salary(5, 5, "2020-01-01", null, "EUR", "92000.00", "100000.00", "NEW_HIRE");
        person(f, 6, "NON_BINARY", 1, 1, 1, "1000000.00");
        person(f, 7, "PREFER_NOT_TO_SAY", 1, 1, 1, "1000000.00");
        person(f, 8, null, 1, 1, 1, "1000000.00");
        f.employee(9, "P9", "Person", "MALE", "TERMINATED", "1.000", 1, 1, 1, "2020-01-01", "2025-12-31");
        f.salary(9, 9, "999999.00");
        // Sales
        person(f, 10, "MALE", 2, 2, 1, "50000.00");
        person(f, 11, "MALE", 2, 2, 1, "70000.00");
        person(f, 12, "FEMALE", 2, 2, 1, "80000.00");
        person(f, 13, "FEMALE", 2, 2, 1, "90000.00");
        person(f, 14, "FEMALE", 2, 2, 1, "100000.00");
        // Operations
        person(f, 15, "MALE", 3, 3, 1, "100000.00");
        person(f, 16, "MALE", 3, 3, 1, "100000.00");
        person(f, 17, "MALE", 3, 3, 1, "100000.00");
        person(f, 18, "FEMALE", 3, 3, 1, "87655.00");
        // Zero: the male's base-currency amount is 0 (allowed: only base_amount is CHECKed positive)
        f.employee(19, "P19", "Person", "MALE", 4, 4, 1);
        f.salary(19, 19, "2020-01-01", null, "USD", "1.00", "0.00", "NEW_HIRE");
        person(f, 20, "FEMALE", 4, 4, 1, "50000.00");
        // Solo
        person(f, 21, "MALE", 5, 5, 1, "70000.00");
        person(f, 22, "MALE", 5, 5, 1, "90000.00");
        // Nonbinary only
        person(f, 23, "NON_BINARY", 6, 6, 1, "60000.00");
    }

    private static void person(SqlFixture f, long id, String gender, long dept, long role, long location,
                               String amount) {
        f.employee(id, "P" + id, "Person", gender, dept, role, location);
        f.salary(id, id, amount);
    }

    private static GenderGapRow rowOf(List<GenderGapRow> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).findFirst().orElseThrow();
    }

    private static void assertPct(BigDecimal actual, String expected) {
        assertThat(actual).as("percentage").isNotNull();
        assertThat(actual.toPlainString()).isEqualTo(expected);
    }

    private List<GenderGapRow> byDepartment() {
        return repository.gaps(GroupBy.DEPARTMENT, ALL, AS_OF);
    }

    @Test
    @DisplayName("FR-4.5: groups are returned in label order, and a group with no MALE or FEMALE employee is absent")
    void departments_areOrderedAndGroupsWithoutMaleOrFemaleAreDropped() {
        assertThat(byDepartment()).extracting(GenderGapRow::label)
                .containsExactly("Engineering", "Operations", "Sales", "Solo", "Zero");
    }

    @Test
    @DisplayName("FR-4.5: Engineering mean gap is (140,000 - 95,000) / 140,000 = 32.14 and median gap 25,000 / 120,000 = 20.83")
    void engineering_gapMatchesHandComputedFigures() {
        GenderGapRow engineering = rowOf(byDepartment(), "Engineering");

        assertPct(engineering.meanGapPct(), "32.14");
        assertPct(engineering.medianGapPct(), "20.83");
    }

    @Test
    @DisplayName("FR-4.5: only MALE and FEMALE are counted (3 and 2): NON_BINARY, PREFER_NOT_TO_SAY, null and the leaver are out")
    void engineering_countsOnlyMaleAndFemale() {
        GenderGapRow engineering = rowOf(byDepartment(), "Engineering");

        assertThat(engineering.maleCount()).isEqualTo(3);
        assertThat(engineering.femaleCount()).isEqualTo(2);
        assertThat(engineering.key()).isEqualTo("1");
    }

    @Test
    @DisplayName("FR-4.5: a positive gap means women are paid less; a negative gap means men are (Sales: -50.00)")
    void sales_negativeGapWhenMenArePaidLess() {
        GenderGapRow sales = rowOf(byDepartment(), "Sales");

        assertPct(sales.meanGapPct(), "-50.00");
        assertPct(sales.medianGapPct(), "-50.00");
        assertThat(sales.maleCount()).isEqualTo(2);
        assertThat(sales.femaleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR-4.5: the gap is rounded HALF_UP: 12.345 becomes 12.35")
    void operations_gapRoundsHalfUp() {
        GenderGapRow operations = rowOf(byDepartment(), "Operations");

        assertPct(operations.meanGapPct(), "12.35");
        assertPct(operations.medianGapPct(), "12.35");
    }

    @Test
    @DisplayName("FR-4.5: a male mean of zero gives null gaps instead of a division error")
    void zeroMaleMean_givesNullGaps() {
        GenderGapRow zero = rowOf(byDepartment(), "Zero");

        assertThat(zero.meanGapPct()).isNull();
        assertThat(zero.medianGapPct()).isNull();
        assertThat(zero.maleCount()).isEqualTo(1);
        assertThat(zero.femaleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-4.5: a group with men only has null gaps and a female count of zero")
    void groupWithoutWomen_hasNullGaps() {
        GenderGapRow solo = rowOf(byDepartment(), "Solo");

        assertThat(solo.meanGapPct()).isNull();
        assertThat(solo.medianGapPct()).isNull();
        assertThat(solo.maleCount()).isEqualTo(2);
        assertThat(solo.femaleCount()).isZero();
    }

    @Test
    @DisplayName("FR-4.5: by job level, L4 is 20,000 / 110,000 = 18.18 and L5 has mean gap 13.28 and median gap -35.71")
    void jobLevel_gapsMatchHandComputedFigures() {
        List<GenderGapRow> byLevel = repository.gaps(GroupBy.JOB_LEVEL, ALL, AS_OF);

        assertThat(byLevel).extracting(GenderGapRow::label).containsExactly("L1", "L2", "L3", "L4", "L5");
        GenderGapRow l4 = rowOf(byLevel, "L4");
        assertPct(l4.meanGapPct(), "18.18");
        assertPct(l4.medianGapPct(), "18.18");
        assertThat(l4.maleCount()).isEqualTo(2);
        assertThat(l4.femaleCount()).isEqualTo(1);
        GenderGapRow l5 = rowOf(byLevel, "L5");
        assertPct(l5.meanGapPct(), "13.28");
        assertPct(l5.medianGapPct(), "-35.71");
        assertThat(l5.maleCount()).isEqualTo(3);
        assertThat(l5.femaleCount()).isEqualTo(4);
        assertThat(l5.key()).isEqualTo("L5");
    }

    @Test
    @DisplayName("FR-4.7: an explicit TERMINATED status brings the leaver in (Engineering: 1 male, 0 female)")
    void statusFilter_terminated_includesTheLeaver() {
        List<GenderGapRow> rows = repository.gaps(GroupBy.DEPARTMENT,
                new EmployeeFilter(null, null, null, EmploymentStatus.TERMINATED, null, null), AS_OF);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).label()).isEqualTo("Engineering");
        assertThat(rows.get(0).maleCount()).isEqualTo(1);
        assertThat(rows.get(0).femaleCount()).isZero();
    }

    @Test
    @DisplayName("FR-4.7: a department filter narrows the groups to Sales")
    void departmentFilter_narrowsTheGroups() {
        List<GenderGapRow> rows = repository.gaps(GroupBy.DEPARTMENT,
                new EmployeeFilter(null, 2L, null, null, null, null), AS_OF);

        assertThat(rows).extracting(GenderGapRow::label).containsExactly("Sales");
    }

    @Test
    @DisplayName("FR-4.5: an empty slice gives no groups")
    void emptySlice_givesNoGroups() {
        assertThat(repository.gaps(GroupBy.DEPARTMENT,
                new EmployeeFilter("nobody-with-this-name", null, null, null, null, null), AS_OF)).isEmpty();
    }
}
