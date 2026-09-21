package com.acme.salary.repository;

import com.acme.salary.model.ImportError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportErrorRepository extends JpaRepository<ImportError, Long> {

    // FR-5.4's error report; backed by idx_import_error_job_row (import_job_id, row_number).
    List<ImportError> findByImportJobIdOrderByRowNumberAsc(Long importJobId);
}
