package com.acme.salary.dto;

import java.util.List;

/**
 * FR-2.2: the page envelope. Our own record rather than a serialised Spring {@code Page}, whose JSON
 * shape is explicitly not a stable contract.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
