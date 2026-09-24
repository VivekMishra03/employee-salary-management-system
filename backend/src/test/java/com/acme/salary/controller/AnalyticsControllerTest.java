package com.acme.salary.controller;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.readmodel.AnalyticsRepository;
import com.acme.salary.readmodel.BucketRow;
import com.acme.salary.model.PayBandAdherence;
import com.acme.salary.readmodel.GenderGapAnalyticsRepository;
import com.acme.salary.readmodel.GenderGapRow;
import com.acme.salary.readmodel.GroupBy;
import com.acme.salary.readmodel.GroupRow;
import com.acme.salary.readmodel.PayBandAnalyticsRepository;
import com.acme.salary.readmodel.PayBandCountsRow;
import com.acme.salary.readmodel.PayBandRow;
import com.acme.salary.readmodel.SummaryRow;
import com.acme.salary.readmodel.TrendAnalyticsRepository;
import com.acme.salary.readmodel.TrendInterval;
import com.acme.salary.readmodel.TrendRow;
import com.acme.salary.service.JwtService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-4.1 - FR-4.3, FR-4.7 over HTTP, behind the real security chain, with the real service (so its
 * validation runs) and a stubbed read model (its SQL is covered by AnalyticsRepositoryTest).
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class AnalyticsControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private AnalyticsRepository repository;
    @MockitoBean
    private PayBandAnalyticsRepository payBandRepository;
    @MockitoBean
    private GenderGapAnalyticsRepository genderGapRepository;
    @MockitoBean
    private TrendAnalyticsRepository trendRepository;

    private String token;

    @BeforeEach
    void issueToken() {
        token = jwtService.issue(1L, "hr.manager@acme.example", AppUserRole.HR_MANAGER).token();
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static final SummaryRow SUMMARY_ROW = new SummaryRow(4, new BigDecimal("350000.00"),
            new BigDecimal("95000.00"), new BigDecimal("90000.00"), new BigDecimal("75000.00"),
            new BigDecimal("110000.00"));

    // ---- security --------------------------------------------------------------------------

    @Test
    @DisplayName("FR-1.2: every analytics endpoint rejects a request without a token, and never reaches the read model")
    void everyEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(BASE + "/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/by-group").param("groupBy", "COUNTRY")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/distribution")).andExpect(status().isUnauthorized());

        verifyNoInteractions(repository);
    }

    // ---- summary ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.1: GET /summary returns the headcount, payroll and pay statistics as JSON numbers")
    void summary_returnsTheStatistics() throws Exception {
        when(repository.summary(any(), any())).thenReturn(SUMMARY_ROW);

        mockMvc.perform(authed(get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headcount").value(4))
                .andExpect(jsonPath("$.totalPayroll").value(350000.00))
                .andExpect(jsonPath("$.mean").value(95000.00))
                .andExpect(jsonPath("$.median").value(90000.00))
                .andExpect(jsonPath("$.p25").value(75000.00))
                .andExpect(jsonPath("$.p75").value(110000.00));
    }

    @Test
    @DisplayName("Assumption 2: GET /summary carries ratesAsOf as an ISO date string")
    void summary_returnsRatesAsOfAsIsoDate() throws Exception {
        when(repository.summary(any(), any())).thenReturn(SUMMARY_ROW);
        when(repository.latestRateDate()).thenReturn(LocalDate.of(2025, 6, 30));

        mockMvc.perform(authed(get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratesAsOf").value("2025-06-30"));
    }

    @Test
    @DisplayName("Assumption 2: ratesAsOf is present and null when there are no exchange rates")
    void summary_withNoRates_returnsNullRatesAsOf() throws Exception {
        when(repository.summary(any(), any())).thenReturn(SUMMARY_ROW);
        when(repository.latestRateDate()).thenReturn(null);

        mockMvc.perform(authed(get(BASE + "/summary")))
                .andExpect(status().isOk())
                // jsonPath().exists() treats a null leaf as absent, so the key's presence is asserted on the text.
                .andExpect(content().string(containsString("\"ratesAsOf\":null")))
                .andExpect(jsonPath("$.ratesAsOf").value(nullValue()));
    }

    @Test
    @DisplayName("Assumption 2: the filter parameters do not reach the rates query")
    void summary_ratesAsOfIsIndependentOfTheFilters() throws Exception {
        when(repository.summary(any(), any())).thenReturn(SUMMARY_ROW);
        when(repository.latestRateDate()).thenReturn(LocalDate.of(2025, 6, 30));

        mockMvc.perform(authed(get(BASE + "/summary").param("countryCode", "de").param("status", "TERMINATED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratesAsOf").value("2025-06-30"));

        // latestRateDate() takes no arguments: the compiler, not a matcher, guarantees the independence.
        verify(repository).latestRateDate();
    }

    @Test
    @DisplayName("FR-4.1: an empty slice is a 200 with null statistics")
    void summary_emptySlice_returnsNullStatistics() throws Exception {
        when(repository.summary(any(), any()))
                .thenReturn(new SummaryRow(0, new BigDecimal("0.00"), null, null, null, null));

        mockMvc.perform(authed(get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headcount").value(0))
                .andExpect(jsonPath("$.median").value(nullValue()));
    }

    @Test
    @DisplayName("FR-4.7: the same filter parameters as GET /employees reach the read model from /summary")
    void summary_filterParametersReachTheReadModel() throws Exception {
        when(repository.summary(any(), any())).thenReturn(SUMMARY_ROW);

        mockMvc.perform(authed(get(BASE + "/summary")
                        .param("q", "ada").param("departmentId", "3").param("countryCode", "us")
                        .param("status", "ACTIVE").param("employmentType", "FULL_TIME").param("jobLevel", "L4")))
                .andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        ArgumentCaptor<LocalDate> asOf = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).summary(filter.capture(), asOf.capture());
        assertThat(filter.getValue()).isEqualTo(
                new EmployeeFilter("ada", 3L, "us", EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME, "L4"));
        assertThat(asOf.getValue()).isNotNull();
    }

    @Test
    @DisplayName("FR-4.7: an unrecognised status is a 400 naming the field, without echoing the value")
    void summary_withUnknownStatus_returns400() throws Exception {
        mockMvc.perform(authed(get(BASE + "/summary").param("status", "BANANA")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='status')]").exists())
                .andExpect(content().string(not(containsString("BANANA"))));

        verifyNoInteractions(repository);
    }

    // ---- by group --------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.2: GET /by-group returns one entry per group with key, label, headcount, median and mean")
    void byGroup_returnsTheGroups() throws Exception {
        when(repository.byGroup(eq(GroupBy.COUNTRY), any(), any())).thenReturn(List.of(
                new GroupRow("DE", "Germany", 2, new BigDecimal("100000.00"), new BigDecimal("100000.00")),
                new GroupRow("US", "United States", 2, new BigDecimal("90000.00"), new BigDecimal("90000.00"))));

        mockMvc.perform(authed(get(BASE + "/by-group").param("groupBy", "COUNTRY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].key").value("DE"))
                .andExpect(jsonPath("$[0].label").value("Germany"))
                .andExpect(jsonPath("$[0].headcount").value(2))
                .andExpect(jsonPath("$[0].median").value(100000.00))
                .andExpect(jsonPath("$[0].mean").value(100000.00))
                .andExpect(jsonPath("$[1].label").value("United States"))
                .andExpect(jsonPath("$[1].median").value(90000.00));
    }

    @Test
    @DisplayName("FR-4.7: the filter parameters reach the read model from /by-group, together with the grouping")
    void byGroup_filterParametersReachTheReadModel() throws Exception {
        when(repository.byGroup(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(authed(get(BASE + "/by-group").param("groupBy", "JOB_LEVEL")
                        .param("departmentId", "3").param("status", "TERMINATED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(repository).byGroup(eq(GroupBy.JOB_LEVEL), filter.capture(), any());
        assertThat(filter.getValue())
                .isEqualTo(new EmployeeFilter(null, 3L, null, EmploymentStatus.TERMINATED, null, null));
    }

    @Test
    @DisplayName("FR-4.2: a missing groupBy is a 400 problem+json with code INVALID_GROUP_BY")
    void byGroup_withoutGroupBy_returns400() throws Exception {
        mockMvc.perform(authed(get(BASE + "/by-group")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_GROUP_BY"))
                .andExpect(jsonPath("$.errors[?(@.field=='groupBy')]").exists());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("FR-4.2: an unknown groupBy is a 400 with code INVALID_GROUP_BY that does not echo the value")
    void byGroup_withUnknownGroupBy_returns400() throws Exception {
        mockMvc.perform(authed(get(BASE + "/by-group").param("groupBy", "SHOE_SIZE")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_GROUP_BY"))
                .andExpect(content().string(not(containsString("SHOE_SIZE"))));

        verifyNoInteractions(repository);
    }

    // ---- distribution ----------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.3: GET /distribution returns the buckets, defaulting to 12")
    void distribution_returnsTheBucketsWithDefaultCount() throws Exception {
        when(repository.distribution(any(), any(), eq(12))).thenReturn(List.of(
                new BucketRow(new BigDecimal("60000.00"), new BigDecimal("100000.00"), 1),
                new BucketRow(new BigDecimal("100000.00"), new BigDecimal("140000.00"), 3)));

        mockMvc.perform(authed(get(BASE + "/distribution")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].lower").value(60000.00))
                .andExpect(jsonPath("$[0].upper").value(100000.00))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[1].count").value(3));
    }

    @Test
    @DisplayName("FR-4.3 / FR-4.7: buckets and the filter parameters reach the read model from /distribution")
    void distribution_bucketsAndFiltersReachTheReadModel() throws Exception {
        when(repository.distribution(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(authed(get(BASE + "/distribution").param("buckets", "5").param("countryCode", "de")
                        .param("q", "grace")))
                .andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        ArgumentCaptor<Integer> buckets = ArgumentCaptor.forClass(Integer.class);
        verify(repository).distribution(filter.capture(), any(), buckets.capture());
        assertThat(buckets.getValue()).isEqualTo(5);
        assertThat(filter.getValue()).isEqualTo(new EmployeeFilter("grace", null, "de", null, null, null));
    }

    @Test
    @DisplayName("FR-4.3: buckets outside 2..50 or not a number is a 400 with code INVALID_BUCKETS that does not echo the value")
    void distribution_withInvalidBuckets_returns400() throws Exception {
        for (String invalid : List.of("1", "51", "zebra")) {
            mockMvc.perform(authed(get(BASE + "/distribution").param("buckets", invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_BUCKETS"))
                    .andExpect(jsonPath("$.errors[?(@.field=='buckets')]").exists())
                    .andExpect(content().string(not(containsString("zebra"))));
        }

        verifyNoInteractions(repository);
    }

    // ---- slice B: security -----------------------------------------------------------------

    @Test
    @DisplayName("FR-1.2: pay-bands, gender-gap and trend reject a request without a token and never reach a read model")
    void sliceBEndpoints_withoutToken_return401() throws Exception {
        mockMvc.perform(get(BASE + "/pay-bands")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/gender-gap").param("groupBy", "DEPARTMENT")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/trend").param("from", "2026-01-01").param("to", "2026-03-31")
                .param("interval", "MONTH")).andExpect(status().isUnauthorized());

        verifyNoInteractions(payBandRepository, genderGapRepository, trendRepository);
    }

    // ---- pay bands -------------------------------------------------------------------------

    private static PayBandRow bandRow() {
        return new PayBandRow(7, "ACME-000007", "Ann Below", "Software Engineer", "L4", "US", "USD",
                new BigDecimal("79999.99"), new BigDecimal("80000.00"), new BigDecimal("100000.00"),
                new BigDecimal("120000.00"), new BigDecimal("0.80"), PayBandAdherence.BELOW);
    }

    @Test
    @DisplayName("FR-4.4: GET /pay-bands returns the four counts and a page of named employees, without any gender field")
    void payBands_returnsCountsAndPage() throws Exception {
        when(payBandRepository.counts(any(), any())).thenReturn(new PayBandCountsRow(3, 20, 5, 2));
        when(payBandRepository.rows(any(), any(), isNull(), eq(20), eq(0L))).thenReturn(List.of(bandRow()));

        mockMvc.perform(authed(get(BASE + "/pay-bands")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.below").value(3))
                .andExpect(jsonPath("$.counts.within").value(20))
                .andExpect(jsonPath("$.counts.above").value(5))
                .andExpect(jsonPath("$.counts.noBand").value(2))
                .andExpect(jsonPath("$.employees.page").value(0))
                .andExpect(jsonPath("$.employees.size").value(20))
                .andExpect(jsonPath("$.employees.totalElements").value(30))
                .andExpect(jsonPath("$.employees.totalPages").value(2))
                .andExpect(jsonPath("$.employees.content[0].employeeCode").value("ACME-000007"))
                .andExpect(jsonPath("$.employees.content[0].fullName").value("Ann Below"))
                .andExpect(jsonPath("$.employees.content[0].jobTitle").value("Software Engineer"))
                .andExpect(jsonPath("$.employees.content[0].jobLevel").value("L4"))
                .andExpect(jsonPath("$.employees.content[0].countryCode").value("US"))
                .andExpect(jsonPath("$.employees.content[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$.employees.content[0].annualisedAmount").value(79999.99))
                .andExpect(jsonPath("$.employees.content[0].bandMin").value(80000.00))
                .andExpect(jsonPath("$.employees.content[0].bandMid").value(100000.00))
                .andExpect(jsonPath("$.employees.content[0].bandMax").value(120000.00))
                .andExpect(jsonPath("$.employees.content[0].compaRatio").value(0.80))
                .andExpect(jsonPath("$.employees.content[0].adherence").value("BELOW"))
                .andExpect(jsonPath("$.employees.content[0].gender").doesNotExist());
    }

    @Test
    @DisplayName("FR-4.4 / FR-4.7: adherence, page, size and the filter parameters reach the read model")
    void payBands_parametersReachTheReadModel() throws Exception {
        when(payBandRepository.counts(any(), any())).thenReturn(new PayBandCountsRow(0, 0, 0, 0));
        when(payBandRepository.rows(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());

        mockMvc.perform(authed(get(BASE + "/pay-bands").param("adherence", "above").param("page", "2")
                        .param("size", "10").param("countryCode", "us").param("status", "ON_LEAVE")))
                .andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(payBandRepository).rows(filter.capture(), any(), eq(PayBandAdherence.ABOVE), eq(10), eq(20L));
        assertThat(filter.getValue())
                .isEqualTo(new EmployeeFilter(null, null, "us", EmploymentStatus.ON_LEAVE, null, null));
    }

    @Test
    @DisplayName("FR-4.4: an invalid adherence, page or size is a 400 with its own stable code and no echo of the value")
    void payBands_invalidParameters_return400() throws Exception {
        mockMvc.perform(authed(get(BASE + "/pay-bands").param("adherence", "MIDDLEZZ")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_ADHERENCE"))
                .andExpect(jsonPath("$.errors[?(@.field=='adherence')]").exists())
                .andExpect(content().string(not(containsString("MIDDLEZZ"))));
        mockMvc.perform(authed(get(BASE + "/pay-bands").param("page", "-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mockMvc.perform(authed(get(BASE + "/pay-bands").param("size", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE"));

        verifyNoInteractions(payBandRepository);
    }

    // ---- gender gap ------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.5: GET /gender-gap returns a shown group with its counts and gaps, and a small group suppressed with nulls")
    void genderGap_returnsShownAndSuppressedGroups() throws Exception {
        when(genderGapRepository.gaps(eq(GroupBy.DEPARTMENT), any(), any())).thenReturn(List.of(
                new GenderGapRow("1", "Engineering", 6, 5, new BigDecimal("12.34"), new BigDecimal("-3.20")),
                new GenderGapRow("2", "Legal", 9, 4, new BigDecimal("5.00"), new BigDecimal("6.00"))));

        mockMvc.perform(authed(get(BASE + "/gender-gap").param("groupBy", "DEPARTMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].label").value("Engineering"))
                .andExpect(jsonPath("$[0].maleCount").value(6))
                .andExpect(jsonPath("$[0].femaleCount").value(5))
                .andExpect(jsonPath("$[0].meanGapPct").value(12.34))
                .andExpect(jsonPath("$[0].medianGapPct").value(-3.20))
                .andExpect(jsonPath("$[0].suppressed").value(false))
                .andExpect(jsonPath("$[1].label").value("Legal"))
                .andExpect(jsonPath("$[1].suppressed").value(true))
                .andExpect(jsonPath("$[1].maleCount").value(nullValue()))
                .andExpect(jsonPath("$[1].femaleCount").value(nullValue()))
                .andExpect(jsonPath("$[1].meanGapPct").value(nullValue()))
                .andExpect(jsonPath("$[1].medianGapPct").value(nullValue()));
    }

    @Test
    @DisplayName("FR-4.5 / FR-4.7: JOB_LEVEL and the filter parameters reach the gender-gap read model")
    void genderGap_parametersReachTheReadModel() throws Exception {
        when(genderGapRepository.gaps(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(authed(get(BASE + "/gender-gap").param("groupBy", "JOB_LEVEL").param("countryCode", "de")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(genderGapRepository).gaps(eq(GroupBy.JOB_LEVEL), filter.capture(), any());
        assertThat(filter.getValue()).isEqualTo(new EmployeeFilter(null, null, "de", null, null, null));
    }

    @Test
    @DisplayName("FR-4.5: groupBy=COUNTRY is a 400 with code UNSUPPORTED_GROUP_BY; a missing or unknown one is INVALID_GROUP_BY")
    void genderGap_badGroupBy_returns400() throws Exception {
        mockMvc.perform(authed(get(BASE + "/gender-gap").param("groupBy", "COUNTRY")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_GROUP_BY"))
                .andExpect(jsonPath("$.errors[?(@.field=='groupBy')]").exists());
        mockMvc.perform(authed(get(BASE + "/gender-gap")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GROUP_BY"));
        mockMvc.perform(authed(get(BASE + "/gender-gap").param("groupBy", "SHOE_SIZE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GROUP_BY"))
                .andExpect(content().string(not(containsString("SHOE_SIZE"))));

        verifyNoInteractions(genderGapRepository);
    }

    // ---- trend -----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: GET /trend returns one labelled point per period, with a null increase where there is none")
    void trend_returnsLabelledPeriods() throws Exception {
        when(trendRepository.trend(eq(TrendInterval.QUARTER), eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 6, 30)), any())).thenReturn(List.of(
                new TrendRow(LocalDate.of(2026, 1, 1), new BigDecimal("843000.00"), new BigDecimal("15.00")),
                new TrendRow(LocalDate.of(2026, 4, 1), new BigDecimal("0.00"), null)));

        mockMvc.perform(authed(get(BASE + "/trend").param("from", "2026-01-01").param("to", "2026-06-30")
                        .param("interval", "quarter")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].period").value("2026-Q1"))
                .andExpect(jsonPath("$[0].totalPayrollUsd").value(843000.00))
                .andExpect(jsonPath("$[0].avgIncreasePct").value(15.00))
                .andExpect(jsonPath("$[1].period").value("2026-Q2"))
                .andExpect(jsonPath("$[1].totalPayrollUsd").value(0.00))
                .andExpect(jsonPath("$[1].avgIncreasePct").value(nullValue()));
    }

    @Test
    @DisplayName("FR-4.7: the filter parameters reach the trend read model")
    void trend_filterParametersReachTheReadModel() throws Exception {
        when(trendRepository.trend(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(authed(get(BASE + "/trend").param("from", "2026-01-01").param("to", "2026-01-31")
                        .param("interval", "MONTH").param("departmentId", "3").param("status", "TERMINATED")))
                .andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(trendRepository).trend(eq(TrendInterval.MONTH), any(), any(), filter.capture());
        assertThat(filter.getValue())
                .isEqualTo(new EmployeeFilter(null, 3L, null, EmploymentStatus.TERMINATED, null, null));
    }

    // ---- FR-4.7: all six filter parameters reach the read model on EVERY endpoint -----------

    /** Sends all six filter parameters. countryCode and jobLevel are deliberately not normalised here. */
    private MockHttpServletRequestBuilder withAllFilters(MockHttpServletRequestBuilder builder) {
        return authed(builder.param("q", "ada byron").param("departmentId", "4").param("countryCode", "de")
                .param("status", "ON_LEAVE").param("employmentType", "PART_TIME").param("jobLevel", "L4"));
    }

    private static void assertAllFilters(EmployeeFilter actual) {
        assertThat(actual.q()).isEqualTo("ada byron");
        assertThat(actual.departmentId()).isEqualTo(4L);
        assertThat(actual.countryCode()).isEqualTo("de");
        assertThat(actual.status()).isEqualTo(EmploymentStatus.ON_LEAVE);
        assertThat(actual.employmentType()).isEqualTo(EmploymentType.PART_TIME);
        assertThat(actual.jobLevel()).isEqualTo("L4");
    }

    @Test
    @DisplayName("FR-4.7: /summary passes all six filter parameters to the read model")
    void allFilters_summary() throws Exception {
        when(repository.summary(any(), any())).thenReturn(SUMMARY_ROW);

        mockMvc.perform(withAllFilters(get(BASE + "/summary"))).andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(repository).summary(filter.capture(), any());
        assertAllFilters(filter.getValue());
    }

    @Test
    @DisplayName("FR-4.7: /by-group passes all six filter parameters to the read model")
    void allFilters_byGroup() throws Exception {
        when(repository.byGroup(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(withAllFilters(get(BASE + "/by-group").param("groupBy", "DEPARTMENT")))
                .andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(repository).byGroup(eq(GroupBy.DEPARTMENT), filter.capture(), any());
        assertAllFilters(filter.getValue());
    }

    @Test
    @DisplayName("FR-4.7: /distribution passes all six filter parameters to the read model")
    void allFilters_distribution() throws Exception {
        when(repository.distribution(any(), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(withAllFilters(get(BASE + "/distribution"))).andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(repository).distribution(filter.capture(), any(), anyInt());
        assertAllFilters(filter.getValue());
    }

    @Test
    @DisplayName("FR-4.7: /pay-bands passes all six filter parameters to both the counts and the page queries")
    void allFilters_payBands() throws Exception {
        when(payBandRepository.counts(any(), any())).thenReturn(new PayBandCountsRow(0, 0, 0, 0));
        when(payBandRepository.rows(any(), any(), any(), anyInt(), anyLong())).thenReturn(List.of());

        mockMvc.perform(withAllFilters(get(BASE + "/pay-bands"))).andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> countsFilter = ArgumentCaptor.forClass(EmployeeFilter.class);
        ArgumentCaptor<EmployeeFilter> rowsFilter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(payBandRepository).counts(countsFilter.capture(), any());
        verify(payBandRepository).rows(rowsFilter.capture(), any(), any(), anyInt(), anyLong());
        assertAllFilters(countsFilter.getValue());
        assertAllFilters(rowsFilter.getValue());
    }

    @Test
    @DisplayName("FR-4.7: /gender-gap passes all six filter parameters to the read model")
    void allFilters_genderGap() throws Exception {
        when(genderGapRepository.gaps(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(withAllFilters(get(BASE + "/gender-gap").param("groupBy", "DEPARTMENT")))
                .andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(genderGapRepository).gaps(eq(GroupBy.DEPARTMENT), filter.capture(), any());
        assertAllFilters(filter.getValue());
    }

    @Test
    @DisplayName("FR-4.7: /trend passes all six filter parameters to the read model")
    void allFilters_trend() throws Exception {
        when(trendRepository.trend(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(withAllFilters(get(BASE + "/trend").param("from", "2026-01-01").param("to", "2026-03-31")
                .param("interval", "MONTH"))).andExpect(status().isOk());

        ArgumentCaptor<EmployeeFilter> filter = ArgumentCaptor.forClass(EmployeeFilter.class);
        verify(trendRepository).trend(eq(TrendInterval.MONTH), any(), any(), filter.capture());
        assertAllFilters(filter.getValue());
    }

    @Test
    @DisplayName("FR-4.6: missing, malformed or out-of-order dates, a bad interval and an oversized span are 400s with stable codes")
    void trend_invalidRequests_return400WithStableCodes() throws Exception {
        assertTrendRejected(null, "2026-01-31", "MONTH", "INVALID_FROM");
        assertTrendRejected("last-tuesday", "2026-01-31", "MONTH", "INVALID_FROM");
        assertTrendRejected("2026-01-01", null, "MONTH", "INVALID_TO");
        assertTrendRejected("2026-01-01", "2026-01-31", null, "INVALID_INTERVAL");
        assertTrendRejected("2026-01-01", "2026-01-31", "WEEK", "INVALID_INTERVAL");
        assertTrendRejected("2026-02-01", "2026-01-31", "MONTH", "INVALID_DATE_RANGE");
        assertTrendRejected("2016-01-01", "2026-01-01", "MONTH", "TREND_SPAN_TOO_LARGE");

        verifyNoInteractions(trendRepository);
    }

    @Test
    @DisplayName("FR-4.6: from=-999999999-01-01 to=+999999999-12-31 by month (int overflow of the period count) is a 400, never a query")
    void trend_overflowingRange_isRejectedWithoutReachingTheReadModel() throws Exception {
        assertTrendRejected("-999999999-01-01", "+999999999-12-31", "MONTH", "DATE_OUT_OF_RANGE");
        assertTrendRejected("1899-12-31", "2026-01-31", "MONTH", "DATE_OUT_OF_RANGE");
        assertTrendRejected("2026-01-01", "2201-01-01", "YEAR", "DATE_OUT_OF_RANGE");

        verifyNoInteractions(trendRepository);
    }

    private void assertTrendRejected(String from, String to, String interval, String code) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE + "/trend");
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }
        if (interval != null) {
            request.param("interval", interval);
        }
        mockMvc.perform(authed(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString("last-tuesday"))));
    }
}
