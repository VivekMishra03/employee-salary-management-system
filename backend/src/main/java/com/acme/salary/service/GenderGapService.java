package com.acme.salary.service;

import com.acme.salary.config.AnalyticsProperties;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.GenderGapStat;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.readmodel.GenderGapAnalyticsRepository;
import com.acme.salary.readmodel.GenderGapRow;
import com.acme.salary.readmodel.GroupBy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * FR-4.5, FR-4.7 (ADR-0013). Owns the rules the SQL does not: which groupings are allowed and the
 * small-group suppression. The read model returns the raw gap for every group; deciding whether it
 * may be shown is a rule that belongs in one testable place, not inside a query.
 */
@Service
@Transactional(readOnly = true)
public class GenderGapService {

    private final GenderGapAnalyticsRepository repository;
    private final AnalyticsProperties properties;
    private final Clock clock;

    public GenderGapService(GenderGapAnalyticsRepository repository, AnalyticsProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public List<GenderGapStat> gaps(String groupBy, EmployeeFilter filter) {
        GroupBy grouping = parseGrouping(groupBy);
        int minimum = properties.minGroupSize();
        return repository.gaps(grouping, filter, LocalDate.now(clock)).stream()
                .map(row -> toStat(row, minimum))
                .toList();
    }

    /**
     * FR-4.5 names department and job level only. COUNTRY is a valid grouping elsewhere in the API,
     * so it is refused with its own code (rather than as an unknown value) to tell the caller it is
     * understood but not offered here.
     */
    private static GroupBy parseGrouping(String groupBy) {
        GroupBy grouping = AnalyticsService.parseGroupBy(groupBy);
        if (grouping == GroupBy.COUNTRY) {
            throw new RequestValidationException("UNSUPPORTED_GROUP_BY", "groupBy",
                    "groupBy must be DEPARTMENT or JOB_LEVEL for the gender pay gap");
        }
        return grouping;
    }

    /**
     * Both genders must reach the minimum. When either does not, the gaps AND the counts are
     * withheld: with the counts published, "4 women, 9 men" next to a department label narrows down
     * who those four women are, which is exactly the re-identification the suppression prevents. A
     * group big enough on both sides can still have a null gap (a zero male mean makes the
     * percentage undefined); that is reported as null, not as suppressed.
     */
    private static GenderGapStat toStat(GenderGapRow row, int minimum) {
        boolean suppressed = row.maleCount() < minimum || row.femaleCount() < minimum;
        if (suppressed) {
            return new GenderGapStat(row.key(), row.label(), null, null, null, null, true);
        }
        return new GenderGapStat(row.key(), row.label(), row.maleCount(), row.femaleCount(), row.meanGapPct(),
                row.medianGapPct(), false);
    }
}
