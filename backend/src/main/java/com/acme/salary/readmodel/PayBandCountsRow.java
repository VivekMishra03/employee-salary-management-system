package com.acme.salary.readmodel;

/** FR-4.4: how many employees of the filtered slice are below, within, above or without a pay band. */
public record PayBandCountsRow(long below, long within, long above, long noBand) {
}
