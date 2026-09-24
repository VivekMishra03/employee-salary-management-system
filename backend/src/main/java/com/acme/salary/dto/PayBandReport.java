package com.acme.salary.dto;

/**
 * FR-4.4: the four counts for the whole filtered slice (independent of the adherence filter and of
 * the page) plus one server-paginated page of the employees.
 */
public record PayBandReport(PayBandCounts counts, PageResponse<PayBandEmployee> employees) {
}
