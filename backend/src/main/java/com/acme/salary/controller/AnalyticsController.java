package com.acme.salary.controller;

import com.acme.salary.dto.AnalyticsSummary;
import com.acme.salary.dto.DistributionBucket;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.GenderGapStat;
import com.acme.salary.dto.GroupStat;
import com.acme.salary.dto.PayBandReport;
import com.acme.salary.dto.TrendPoint;
import com.acme.salary.service.AnalyticsService;
import com.acme.salary.service.GenderGapService;
import com.acme.salary.service.PayBandService;
import com.acme.salary.service.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FR-4.1 - FR-4.6 (requirements.md section 7). Every endpoint takes the same filter parameters as
 * {@code GET /employees} (FR-4.7). Thin: parameter validation and the SQL live below. Behind the
 * default-deny security chain, so no route is registered in SecurityConfig.
 *
 * <p>{@code groupBy}, {@code buckets}, {@code adherence}, {@code page}, {@code size}, {@code from},
 * {@code to} and {@code interval} are taken as raw text and validated by the services, so a missing
 * or invalid value gets the API's own stable error code instead of a framework message that quotes
 * the input.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final PayBandService payBandService;
    private final GenderGapService genderGapService;
    private final TrendService trendService;

    public AnalyticsController(AnalyticsService analyticsService, PayBandService payBandService,
                               GenderGapService genderGapService, TrendService trendService) {
        this.analyticsService = analyticsService;
        this.payBandService = payBandService;
        this.genderGapService = genderGapService;
        this.trendService = trendService;
    }

    /** FR-4.1 */
    @GetMapping("/summary")
    public AnalyticsSummary summary(EmployeeFilter filter) {
        return analyticsService.summary(filter);
    }

    /** FR-4.2: {@code groupBy} is DEPARTMENT, COUNTRY or JOB_LEVEL. */
    @GetMapping("/by-group")
    public List<GroupStat> byGroup(@RequestParam(required = false) String groupBy, EmployeeFilter filter) {
        return analyticsService.byGroup(groupBy, filter);
    }

    /** FR-4.3: {@code buckets} is 2 to 50, default 12. */
    @GetMapping("/distribution")
    public List<DistributionBucket> distribution(@RequestParam(required = false) String buckets,
                                                 EmployeeFilter filter) {
        return analyticsService.distribution(buckets, filter);
    }

    /**
     * FR-4.4: the four counts for the whole filtered slice plus a page of named employees, lowest
     * compa-ratio first. {@code adherence} narrows the page only; {@code page} is 0-based, {@code
     * size} defaults to 20 and is capped at 100.
     */
    @GetMapping("/pay-bands")
    public PayBandReport payBands(@RequestParam(required = false) String adherence,
                                  @RequestParam(required = false) String page,
                                  @RequestParam(required = false) String size, EmployeeFilter filter) {
        return payBandService.report(adherence, page, size, filter);
    }

    /** FR-4.5: {@code groupBy} is DEPARTMENT or JOB_LEVEL. Small groups come back suppressed. */
    @GetMapping("/gender-gap")
    public List<GenderGapStat> genderGap(@RequestParam(required = false) String groupBy, EmployeeFilter filter) {
        return genderGapService.gaps(groupBy, filter);
    }

    /** FR-4.6: {@code from} and {@code to} are ISO dates, {@code interval} is MONTH, QUARTER or YEAR. */
    @GetMapping("/trend")
    public List<TrendPoint> trend(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String interval, EmployeeFilter filter) {
        return trendService.trend(from, to, interval, filter);
    }
}
