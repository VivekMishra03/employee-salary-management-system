package com.acme.salary.service;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.TrendPoint;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.readmodel.TrendAnalyticsRepository;
import com.acme.salary.readmodel.TrendInterval;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * FR-4.6, FR-4.7 (ADR-0013). Validates the request and labels the periods. There is no Clock here:
 * both ends of the range come from the caller, so the result does not depend on today's date.
 *
 * <p>All parameters arrive as text so a bad value gets this API's stable code and is never echoed
 * (a framework conversion error would quote it).
 */
@Service
@Transactional(readOnly = true)
public class TrendService {

    /** Bounds the work of one request: the SQL generates one row per period and scans salary records once. */
    static final int MAX_PERIODS = 120;

    /**
     * Supported calendar years, inclusive. {@link LocalDate} accepts years of +-999999999, which the
     * database turns into infinite dates and an unbounded generate_series; no payroll history lies
     * outside this window, so anything beyond it is rejected before it can cost anything.
     */
    static final int MIN_YEAR = 1900;
    static final int MAX_YEAR = 2200;

    private final TrendAnalyticsRepository repository;

    public TrendService(TrendAnalyticsRepository repository) {
        this.repository = repository;
    }

    public List<TrendPoint> trend(String from, String to, String interval, EmployeeFilter filter) {
        LocalDate fromDate = parseDate(from, "INVALID_FROM", "from");
        LocalDate toDate = parseDate(to, "INVALID_TO", "to");
        TrendInterval bucket = parseInterval(interval);
        requireSupportedYear(fromDate, "from");
        requireSupportedYear(toDate, "to");
        if (fromDate.isAfter(toDate)) {
            throw new RequestValidationException("INVALID_DATE_RANGE", "from", "from must not be after to");
        }
        // Counted in calendar buckets touched, not days: a range starting on the 31st still
        // produces (and costs) that whole month.
        if (bucket.periodCount(fromDate, toDate) > MAX_PERIODS) {
            throw new RequestValidationException("TREND_SPAN_TOO_LARGE", "to",
                    "the range spans more than " + MAX_PERIODS + " periods");
        }
        return repository.trend(bucket, fromDate, toDate, filter).stream()
                .map(r -> new TrendPoint(bucket.label(r.periodStart()), r.totalPayroll(), r.avgIncreasePct()))
                .toList();
    }

    private static void requireSupportedYear(LocalDate date, String field) {
        if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
            // The rejected value is deliberately not echoed.
            throw new RequestValidationException("DATE_OUT_OF_RANGE", field,
                    field + " must be in the years " + MIN_YEAR + " to " + MAX_YEAR);
        }
    }

    private static LocalDate parseDate(String text, String code, String field) {
        if (text != null) {
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException e) {
                // falls through to the same rejection as a missing value
            }
        }
        throw new RequestValidationException(code, field, field + " must be a date in the form yyyy-MM-dd");
    }

    private static TrendInterval parseInterval(String interval) {
        if (interval != null) {
            String normalised = interval.trim().toUpperCase(Locale.ROOT);
            for (TrendInterval candidate : TrendInterval.values()) {
                if (candidate.name().equals(normalised)) {
                    return candidate;
                }
            }
        }
        throw new RequestValidationException("INVALID_INTERVAL", "interval",
                "interval must be one of MONTH, QUARTER, YEAR");
    }
}
