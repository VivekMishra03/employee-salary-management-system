package com.acme.salary.repository;

import com.acme.salary.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * {@link JpaSpecificationExecutor} carries the dynamic, combinable filters of FR-2.3 / FR-2.4. The
 * specifications themselves are built in the service layer (EmployeeSpecifications), because they
 * are derived from an API-level filter type that the repository layer must not depend on.
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    boolean existsByEmployeeCode(String employeeCode);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    // FR-2.5: direct reports, backed by idx_employee_manager.
    List<Employee> findByManagerIdOrderByLastNameAscFirstNameAsc(Long managerId);
}
