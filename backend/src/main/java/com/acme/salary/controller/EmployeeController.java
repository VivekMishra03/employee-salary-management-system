package com.acme.salary.controller;

import com.acme.salary.dto.CreateEmployeeRequest;
import com.acme.salary.dto.EmployeeDetail;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.EmployeeListItem;
import com.acme.salary.dto.PageResponse;
import com.acme.salary.dto.UpdateEmployeeRequest;
import com.acme.salary.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** FR-2 (requirements.md section 7). Thin: parses, validates, delegates to {@link EmployeeService}. */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    /** FR-2.2 - FR-2.4. Page size is capped by {@code spring.data.web.pageable.max-page-size}. */
    @GetMapping
    public PageResponse<EmployeeListItem> list(EmployeeFilter filter, Pageable pageable) {
        return employeeService.search(filter, pageable);
    }

    @GetMapping("/{id}")
    public EmployeeDetail get(@PathVariable Long id) {
        return employeeService.get(id);
    }

    @PostMapping
    public ResponseEntity<EmployeeDetail> create(@Valid @RequestBody CreateEmployeeRequest request) {
        EmployeeDetail created = employeeService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** FR-2.6: the body's {@code version} must match the current one, or the response is a 409. */
    @PutMapping("/{id}")
    public EmployeeDetail update(@PathVariable Long id, @Valid @RequestBody UpdateEmployeeRequest request) {
        return employeeService.update(id, request);
    }

    /** Soft delete: the employee becomes TERMINATED; nothing is removed. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        employeeService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
