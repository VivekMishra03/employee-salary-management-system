package com.acme.salary.readmodel;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * FR-4.6: the calendar bucket sizes of the payroll trend. A bucket is a whole calendar month,
 * quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec) or year. {@link #months()} is the only thing the SQL
 * needs (it steps from one bucket start to the next); bucketing, counting and labelling stay in Java
 * so the request limit can be enforced before any query runs. It is a fixed constant, never user
 * input, so it is safe in SQL text.
 */
public enum TrendInterval {
    MONTH(1),
    QUARTER(3),
    YEAR(12);

    private final int months;

    TrendInterval(int months) {
        this.months = months;
    }

    public int months() {
        return months;
    }

    /** First day of the calendar bucket containing {@code date}. */
    public LocalDate bucketStart(LocalDate date) {
        int firstMonth = ((date.getMonthValue() - 1) / months) * months + 1;
        return LocalDate.of(date.getYear(), firstMonth, 1);
    }

    /** {@code 2026-03}, {@code 2026-Q1} or {@code 2026}. */
    public String label(LocalDate bucketStart) {
        return switch (this) {
            case MONTH -> "%04d-%02d".formatted(bucketStart.getYear(), bucketStart.getMonthValue());
            case QUARTER -> "%04d-Q%d".formatted(bucketStart.getYear(), (bucketStart.getMonthValue() - 1) / 3 + 1);
            case YEAR -> "%04d".formatted(bucketStart.getYear());
        };
    }

    /**
     * How many calendar buckets touch the range {@code [from, to]}, both ends included. A {@code long}
     * on purpose: {@link LocalDate} spans billions of months, and narrowing to {@code int} would wrap
     * a huge range to a negative count that slips under the request limit.
     */
    public long periodCount(LocalDate from, LocalDate to) {
        long monthsBetween = ChronoUnit.MONTHS.between(bucketStart(from), bucketStart(to));
        return monthsBetween / months + 1;
    }
}
