package com.acme.salary.service;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.PayBandCounts;
import com.acme.salary.dto.PayBandEmployee;
import com.acme.salary.dto.PayBandReport;
import com.acme.salary.dto.PageResponse;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.model.PayBandAdherence;
import com.acme.salary.readmodel.PayBandAnalyticsRepository;
import com.acme.salary.readmodel.PayBandCountsRow;
import com.acme.salary.readmodel.PayBandRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * FR-4.4, FR-4.7 (ADR-0013). Owns the rules around the read model: "today" comes from the injected
 * {@link Clock} (NFR-3), the request parameters are validated, and the page envelope is derived from
 * the counts (so no second COUNT query is needed for it).
 *
 * <p>{@code adherence}, {@code page} and {@code size} arrive as text on purpose, so that a bad value
 * is answered with this API's own stable code and never echoed back (the framework's conversion
 * error would quote it).
 */
@Service
@Transactional(readOnly = true)
public class PayBandService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final PayBandAnalyticsRepository repository;
    private final Clock clock;

    public PayBandService(PayBandAnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PayBandReport report(String adherence, String page, String size, EmployeeFilter filter) {
        PayBandAdherence adherenceFilter = parseAdherence(adherence);
        int pageNumber = parseNonNegative(page, 0, "INVALID_PAGE", "page", "page must be a whole number, 0 or more");
        int pageSize = Math.min(MAX_PAGE_SIZE, parsePositive(size));
        LocalDate asOf = LocalDate.now(clock);

        PayBandCountsRow counts = repository.counts(filter, asOf);
        // long multiplication: page 2147483647 at size 100 does not fit in an int.
        long offset = (long) pageNumber * pageSize;
        List<PayBandEmployee> content = repository.rows(filter, asOf, adherenceFilter, pageSize, offset).stream()
                .map(PayBandService::toDto)
                .toList();

        long total = totalFor(adherenceFilter, counts);
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new PayBandReport(new PayBandCounts(counts.below(), counts.within(), counts.above(), counts.noBand()),
                new PageResponse<>(content, pageNumber, pageSize, total, totalPages));
    }

    /** The counts always describe the whole slice; the page envelope describes only the requested class. */
    private static long totalFor(PayBandAdherence adherence, PayBandCountsRow counts) {
        if (adherence == null) {
            return counts.below() + counts.within() + counts.above() + counts.noBand();
        }
        return switch (adherence) {
            case BELOW -> counts.below();
            case WITHIN -> counts.within();
            case ABOVE -> counts.above();
            case NO_BAND -> counts.noBand();
        };
    }

    private static PayBandAdherence parseAdherence(String adherence) {
        if (adherence == null) {
            return null;
        }
        String normalised = adherence.trim().toUpperCase(Locale.ROOT);
        for (PayBandAdherence candidate : PayBandAdherence.values()) {
            if (candidate.name().equals(normalised)) {
                return candidate;
            }
        }
        throw new RequestValidationException("INVALID_ADHERENCE", "adherence",
                "adherence must be one of BELOW, WITHIN, ABOVE, NO_BAND");
    }

    private static int parsePositive(String size) {
        int parsed = parseNonNegative(size, DEFAULT_PAGE_SIZE, "INVALID_SIZE", "size",
                "size must be a whole number, 1 or more");
        if (parsed < 1) {
            throw new RequestValidationException("INVALID_SIZE", "size", "size must be a whole number, 1 or more");
        }
        return parsed;
    }

    /** Absent means the default; present but not a non-negative int is rejected. */
    private static int parseNonNegative(String text, int defaultValue, String code, String field, String message) {
        if (text == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(text.trim());
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // falls through to the same rejection as a negative number
        }
        throw new RequestValidationException(code, field, message);
    }

    private static PayBandEmployee toDto(PayBandRow r) {
        return new PayBandEmployee(r.employeeId(), r.employeeCode(), r.fullName(), r.jobTitle(), r.jobLevel(),
                r.countryCode(), r.currencyCode(), r.annualisedAmount(), r.bandMin(), r.bandMid(), r.bandMax(),
                r.compaRatio(), r.adherence());
    }
}
