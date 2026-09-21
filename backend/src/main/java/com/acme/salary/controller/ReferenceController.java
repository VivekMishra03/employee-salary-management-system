package com.acme.salary.controller;

import com.acme.salary.dto.ReferenceData.DepartmentDto;
import com.acme.salary.dto.ReferenceData.JobRoleDto;
import com.acme.salary.dto.ReferenceData.LocationDto;
import com.acme.salary.service.ReferenceDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Requirements.md section 7: {@code /reference/*}, the lookup lists behind the UI's filters and forms. */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    private final ReferenceDataService referenceDataService;

    public ReferenceController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/departments")
    public List<DepartmentDto> departments() {
        return referenceDataService.departments();
    }

    @GetMapping("/locations")
    public List<LocationDto> locations() {
        return referenceDataService.locations();
    }

    @GetMapping("/job-roles")
    public List<JobRoleDto> jobRoles() {
        return referenceDataService.jobRoles();
    }
}
