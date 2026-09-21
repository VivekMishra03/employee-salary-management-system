package com.acme.salary.dto;

/**
 * The small, slowly-changing lookup lists that feed the UI's filters and forms (section 7,
 * {@code /reference/*}). Grouped in one file because each is a three-line record.
 */
public final class ReferenceData {

    private ReferenceData() {
    }

    public record DepartmentDto(Long id, String code, String name, Long parentDepartmentId) {
    }

    public record LocationDto(Long id, String countryCode, String countryName, String city, String currencyCode) {
    }

    public record JobRoleDto(Long id, String title, String jobFamily, String jobLevel) {
    }
}
