package com.acme.salary.service;

import com.acme.salary.exception.RequestValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-2.2: sort validation and normalisation, as a pure unit test -- no Spring context, no database.
 *
 * <p>The id tiebreaker is tested here rather than through paged queries because whether a database
 * returns tied rows in a consistent order is undefined: a paging test against a handful of rows
 * passes with or without the tiebreaker (found by removing it and watching the suite stay green).
 * Asserting the sort that is actually handed to the repository is the deterministic way to pin it.
 */
class EmployeeSortTest {

    private static List<String> described(Sort sort) {
        return sort.stream().map(o -> o.getProperty() + ":" + o.getDirection()).toList();
    }

    @Test
    @DisplayName("FR-2.2: a requested sort gets the id as a final tiebreaker")
    void effectiveSort_appendsTheIdTiebreaker() {
        assertThat(described(EmployeeService.effectiveSort(Sort.by("lastName"))))
                .containsExactly("lastName:ASC", "id:ASC");
    }

    @Test
    @DisplayName("FR-2.2: the requested direction is preserved; only the tiebreaker is ascending")
    void effectiveSort_preservesDescendingOrder() {
        assertThat(described(EmployeeService.effectiveSort(Sort.by(Sort.Direction.DESC, "hireDate"))))
                .containsExactly("hireDate:DESC", "id:ASC");
    }

    @Test
    @DisplayName("FR-2.2: several requested sort keys keep their order, then the tiebreaker")
    void effectiveSort_keepsMultipleKeysInOrder() {
        assertThat(described(EmployeeService.effectiveSort(Sort.by("lastName", "firstName"))))
                .containsExactly("lastName:ASC", "firstName:ASC", "id:ASC");
    }

    @Test
    @DisplayName("FR-2.2: with no sort requested the list defaults to last name, first name, then id")
    void effectiveSort_withNoSort_usesTheDefaultOrdering() {
        assertThat(described(EmployeeService.effectiveSort(Sort.unsorted())))
                .containsExactly("lastName:ASC", "firstName:ASC", "id:ASC");
    }

    @Test
    @DisplayName("FR-2.2: every field the API documents as sortable is accepted")
    void effectiveSort_acceptsEverySortableField() {
        for (String field : List.of("employeeCode", "firstName", "lastName", "email", "hireDate",
                "employmentStatus", "employmentType")) {
            assertThat(EmployeeService.effectiveSort(Sort.by(field))).isNotNull();
        }
    }

    @Test
    @DisplayName("FR-2.2: a field outside the whitelist is rejected, including sensitive and internal ones")
    void effectiveSort_rejectsFieldsOutsideTheWhitelist() {
        // gender is deliberately absent: requirements.md assumption 3 keeps it off the directory list.
        for (String field : List.of("passwordHash", "gender", "version", "managerId", "nonsense", "id")) {
            assertThatThrownBy(() -> EmployeeService.effectiveSort(Sort.by(field)))
                    .as("sorting by %s", field)
                    .isInstanceOf(RequestValidationException.class);
        }
    }

    @Test
    @DisplayName("FR-2.2: one bad field among valid ones still rejects the whole sort")
    void effectiveSort_rejectsAMixOfValidAndInvalidFields() {
        assertThatThrownBy(() -> EmployeeService.effectiveSort(Sort.by("lastName", "gender")))
                .isInstanceOf(RequestValidationException.class);
    }
}
