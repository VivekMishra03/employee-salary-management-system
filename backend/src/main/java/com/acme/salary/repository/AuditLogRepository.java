package com.acme.salary.repository;

import com.acme.salary.model.AuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * FR-3.7: append-only. Deliberately extends the bare {@link Repository} marker rather than
 * {@code JpaRepository}, so the delete/deleteAll/deleteById family is never inherited -- "no update
 * or delete path exists in code" is enforced by the type, not by convention.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog auditLog);

    Optional<AuditLog> findById(Long id);

    // Backed by idx_audit_log_entity (entity_type, entity_id, changed_at DESC).
    List<AuditLog> findByEntityTypeAndEntityIdOrderByChangedAtDesc(String entityType, Long entityId);
}
