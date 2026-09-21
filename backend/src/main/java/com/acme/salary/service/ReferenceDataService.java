package com.acme.salary.service;

import com.acme.salary.dto.ReferenceData.DepartmentDto;
import com.acme.salary.dto.ReferenceData.JobRoleDto;
import com.acme.salary.dto.ReferenceData.LocationDto;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Section 7 {@code /reference/*}: the small lookup lists behind the UI's filters and forms. Full
 * lists, unpaginated, on purpose -- they are bounded by the organisation's structure (departments,
 * offices, job roles), not by the 10,000 employees.
 */
@Service
@Transactional(readOnly = true)
public class ReferenceDataService {

    private final DepartmentRepository departmentRepository;
    private final LocationRepository locationRepository;
    private final JobRoleRepository jobRoleRepository;

    public ReferenceDataService(DepartmentRepository departmentRepository, LocationRepository locationRepository,
                                JobRoleRepository jobRoleRepository) {
        this.departmentRepository = departmentRepository;
        this.locationRepository = locationRepository;
        this.jobRoleRepository = jobRoleRepository;
    }

    public List<DepartmentDto> departments() {
        return departmentRepository.findAll(Sort.by("name")).stream()
                .map(d -> new DepartmentDto(d.getId(), d.getCode(), d.getName(), d.getParentDepartmentId())).toList();
    }

    public List<LocationDto> locations() {
        return locationRepository.findAll(Sort.by("countryName", "city")).stream()
                .map(l -> new LocationDto(l.getId(), l.getCountryCode(), l.getCountryName(), l.getCity(),
                        l.getCurrencyCode())).toList();
    }

    public List<JobRoleDto> jobRoles() {
        return jobRoleRepository.findAll(Sort.by("jobFamily", "jobLevel", "title")).stream()
                .map(r -> new JobRoleDto(r.getId(), r.getTitle(), r.getJobFamily(), r.getJobLevel())).toList();
    }
}
