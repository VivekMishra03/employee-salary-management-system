package com.acme.salary.service;

import com.acme.salary.exception.RequestValidationException;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-3.2 / FR-3.6: the temporal transition rules, as a pure function with no database.
 *
 * <p>An employee's salary history is a timeline of half-open intervals {@code [from, to)} that
 * tiles the period from the hire date onward with no gap and no overlap; a null {@code to} means
 * "still in force". Given the existing intervals and a new effective date, {@link #plan} decides
 * how to add a record while keeping that true:
 *
 * <ul>
 *   <li>the record <em>in force on the effective date</em> is cut to end there, and</li>
 *   <li>the new record runs from the effective date to wherever that record used to end -- so a
 *       back-dated change slots into the middle of history and a raise on the latest record stays
 *       open-ended.</li>
 * </ul>
 *
 * <p>A change effective on the same day an existing record starts supersedes it: that record is cut
 * to zero length rather than deleted (requirements.md assumption 4 -- corrections supersede, history
 * is never erased). Zero-length intervals contain no date, so they are never "in force" and are
 * ignored here.
 *
 * <p>This is half of the two-place enforcement in requirements.md section 6.3; the database's
 * EXCLUDE constraint is the other half and backstops any path that bypasses this class.
 */
final class SalaryTimeline {

    /** An existing record's interval. {@code to} is null while it is still in force. */
    record Interval(Long id, LocalDate from, LocalDate to) {

        boolean contains(LocalDate date) {
            return !date.isBefore(from) && (to == null || date.isBefore(to));
        }
    }

    /**
     * @param recordToCloseId the record to cut to end on the effective date, or null if none
     * @param newEffectiveTo  where the new record ends; null means open-ended
     */
    record Plan(Long recordToCloseId, LocalDate newEffectiveTo) {
    }

    private SalaryTimeline() {
    }

    static Plan plan(LocalDate hireDate, LocalDate terminationDate, List<Interval> existing, LocalDate effectiveFrom) {
        if (effectiveFrom.isBefore(hireDate)) {
            throw new RequestValidationException("EFFECTIVE_BEFORE_HIRE", "effectiveFrom",
                    "A salary cannot take effect before the employee's hire date");
        }
        if (terminationDate != null && effectiveFrom.isAfter(terminationDate)) {
            throw new RequestValidationException("EFFECTIVE_AFTER_TERMINATION", "effectiveFrom",
                    "A salary cannot take effect after the employee's termination date");
        }
        if (existing.isEmpty()) {
            // Anything later than the hire date would leave the days in between with no salary.
            if (!effectiveFrom.equals(hireDate)) {
                throw new RequestValidationException("GAP_AFTER_HIRE", "effectiveFrom",
                        "The first salary record must take effect on the hire date");
            }
            return new Plan(null, null);
        }
        Interval covering = existing.stream()
                .filter(interval -> interval.contains(effectiveFrom))
                .findFirst()
                .orElseThrow(() -> new RequestValidationException("TIMELINE_GAP", "effectiveFrom",
                        "No existing salary record covers that date, so recording it would leave a gap"));
        return new Plan(covering.id(), covering.to());
    }
}
