package com.acme.salary.service;

import com.acme.salary.dto.AnalyticsSummary;
import com.acme.salary.dto.DistributionBucket;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.GroupStat;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.readmodel.AnalyticsRepository;
import com.acme.salary.readmodel.GroupBy;
import com.acme.salary.readmodel.SummaryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * FR-4.1 - FR-4.3, FR-4.7 (ADR-0013). Owns the rules around the read model: which date "current"
 * means (today, from the injected {@link Clock}, NFR-3) and validating the request parameters. The
 * SQL itself lives in {@link AnalyticsRepository}.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    static final int DEFAULT_BUCKETS = 12;
    static final int MIN_BUCKETS = 2;
    static final int MAX_BUCKETS = 50;

    private final AnalyticsRepository repository;
    private final Clock clock;

    public AnalyticsService(AnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AnalyticsSummary summary(EmployeeFilter filter) {
        SummaryRow row = repository.summary(filter, today());
        return new AnalyticsSummary(row.headcount(), row.totalPayroll(), row.mean(), row.median(), row.p25(),
                row.p75(), repository.latestRateDate());
    }

    public List<GroupStat> byGroup(String groupBy, EmployeeFilter filter) {
        GroupBy grouping = parseGroupBy(groupBy);
        return repository.byGroup(grouping, filter, today()).stream()
                .map(g -> new GroupStat(g.key(), g.label(), g.headcount(), g.median(), g.mean()))
                .toList();
    }

    /**
     * {@code buckets} arrives as text on purpose: bound as an int by Spring, a non-numeric value
     * would be reported by the framework with the rejected text quoted in the problem detail, and
     * this API never echoes input back in errors.
     */
    public List<DistributionBucket> distribution(String buckets, EmployeeFilter filter) {
        int count = parseBuckets(buckets);
        return repository.distribution(filter, today(), count).stream()
                .map(b -> new DistributionBucket(b.lower(), b.upper(), b.count()))
                .toList();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    static GroupBy parseGroupBy(String groupBy) {
        if (groupBy != null && !groupBy.isBlank()) {
            String normalised = groupBy.trim().toUpperCase(Locale.ROOT);
            for (GroupBy candidate : GroupBy.values()) {
                if (candidate.name().equals(normalised)) {
                    return candidate;
                }
            }
        }
        throw new RequestValidationException("INVALID_GROUP_BY", "groupBy",
                "groupBy must be one of " + String.join(", ", Arrays.stream(GroupBy.values()).map(Enum::name).toList()));
    }

    private static int parseBuckets(String buckets) {
        if (buckets == null) {
            return DEFAULT_BUCKETS;
        }
        try {
            int parsed = Integer.parseInt(buckets.trim());
            if (parsed >= MIN_BUCKETS && parsed <= MAX_BUCKETS) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // falls through to the same rejection as an out-of-range number
        }
        throw new RequestValidationException("INVALID_BUCKETS", "buckets",
                "buckets must be a whole number between " + MIN_BUCKETS + " and " + MAX_BUCKETS);
    }
}
