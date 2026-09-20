package com.acme.salary.repository;

import com.acme.salary.model.SalaryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalaryRecordRepository extends JpaRepository<SalaryRecord, Long> {

    // Backed by idx_salary_record_employee_history (employee_id, effective_from DESC) --
    // requirements.md section 6.2's stated index, and FR-3.1's full salary-history read.
    List<SalaryRecord> findByEmployeeIdOrderByEffectiveFromDesc(Long employeeId);
}
