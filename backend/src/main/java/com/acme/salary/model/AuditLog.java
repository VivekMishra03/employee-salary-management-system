package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * requirements.md section 6.2 (FR-3.7, NFR-7) — an immutable record of who changed what, and when.
 *
 * <p>Append-only: "no update or delete path exists in code". This class has no setters and is
 * marked {@code @Immutable}, and {@code AuditLogRepository} exposes only save and reads.
 * {@code before_state}/{@code after_state} are JSONB columns holding the entity's serialised
 * state; they are carried here as raw JSON text so this class stays independent of the shape of
 * whatever entity it is auditing.
 */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_id_seq")
    @SequenceGenerator(name = "audit_log_id_seq", sequenceName = "audit_log_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditAction action;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @CreatedDate
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private String afterState;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(String entityType, Long entityId, AuditAction action, Long actorUserId,
                    String beforeState, String afterState) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorUserId = actorUserId;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    public Long getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public AuditAction getAction() {
        return action;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }
}
