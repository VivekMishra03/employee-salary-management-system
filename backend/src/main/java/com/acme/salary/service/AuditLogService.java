package com.acme.salary.service;

import com.acme.salary.model.AuditAction;
import com.acme.salary.model.AuditLog;
import com.acme.salary.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-3.7 / NFR-7: writes the append-only audit trail.
 *
 * <p>{@code Propagation.MANDATORY}: an audit entry can only be written <em>inside</em> the
 * transaction of the mutation it describes, and this method refuses to run outside one. That is what
 * makes the audit trail atomic with the change: the entry commits with it or rolls back with it, so
 * there is never an audited change that did not happen or a change with no audit entry.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param before the entity's state before the change, or null for a CREATE
     * @param after  the entity's state after the change, or null for a DELETE
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, AuditAction action, Long actorUserId, Object before,
                       Object after) {
        auditLogRepository.save(new AuditLog(entityType, entityId, action, actorUserId, toJson(before), toJson(after)));
    }

    private String toJson(Object state) {
        if (state == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            // Failing loudly rolls back the mutation, which is right: an unauditable change must not commit.
            throw new IllegalStateException("Could not serialise audit state", e);
        }
    }
}
