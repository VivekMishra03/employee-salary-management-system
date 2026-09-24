package com.acme.salary.dto;

/** FR-4.4: how many employees of the WHOLE filtered slice are below, within, above or without a pay band. */
public record PayBandCounts(long below, long within, long above, long noBand) {
}
