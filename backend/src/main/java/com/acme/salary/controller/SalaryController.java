package com.acme.salary.controller;

import com.acme.salary.dto.AuthenticatedUser;
import com.acme.salary.dto.EmployeeDetail.SalarySummary;
import com.acme.salary.dto.RecordSalaryRequest;
import com.acme.salary.service.SalaryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** FR-3 (requirements.md section 7). Thin: the rules live in {@link SalaryService}. */
@RestController
@RequestMapping("/api/v1/employees/{employeeId}/salaries")
public class SalaryController {

    private final SalaryService salaryService;

    public SalaryController(SalaryService salaryService) {
        this.salaryService = salaryService;
    }

    /** FR-3.1: the employee's full salary history, newest first. */
    @GetMapping
    public List<SalarySummary> history(@PathVariable Long employeeId) {
        return salaryService.history(employeeId);
    }

    /**
     * FR-3.2 - FR-3.6. The actor is the authenticated user from the token, never a request field, so
     * a change cannot be attributed to someone else (FR-3.7).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SalarySummary record(@PathVariable Long employeeId, @Valid @RequestBody RecordSalaryRequest request,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return salaryService.record(employeeId, request, user.userId());
    }
}
