package com.agentforge.core.agent.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_action_audit_event")
public class AgentAuditEvent {

    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "approval_id", nullable = false) private UUID approvalId;
    @Column(name = "actor_user_id", nullable = false) private UUID actorUserId;
    @Enumerated(EnumType.STRING) @Column(name = "action_type", nullable = false, length = 24)
    private AgentActionType actionType;
    @Column(name = "target_id") private UUID targetId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 16)
    private AgentAuditEventType eventType;
    @Column(nullable = false, length = 32) private String result;
    @Column(name = "request_id", nullable = false, length = 100) private String requestId;
    @Column(name = "idempotency_key", length = 100) private String idempotencyKey;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected AgentAuditEvent() {
    }

    private AgentAuditEvent(
            UUID projectId,
            UUID approvalId,
            UUID actorUserId,
            AgentActionType actionType,
            UUID targetId,
            AgentAuditEventType eventType,
            String requestId,
            String idempotencyKey,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.projectId = Objects.requireNonNull(projectId);
        this.approvalId = Objects.requireNonNull(approvalId);
        this.actorUserId = Objects.requireNonNull(actorUserId);
        this.actionType = Objects.requireNonNull(actionType);
        this.targetId = targetId;
        this.eventType = Objects.requireNonNull(eventType);
        this.result = eventType.name();
        this.requestId = Objects.requireNonNull(requestId);
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static AgentAuditEvent record(
            AgentTaskAction action,
            UUID actorUserId,
            AgentAuditEventType eventType,
            String requestId,
            String idempotencyKey,
            Instant createdAt) {
        return new AgentAuditEvent(
                action.getProjectId(), action.getId(), actorUserId, action.getActionType(),
                action.getTaskId() == null ? action.getResultTaskId() : action.getTaskId(),
                eventType, requestId, idempotencyKey, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getApprovalId() { return approvalId; }
    public UUID getActorUserId() { return actorUserId; }
    public AgentActionType getActionType() { return actionType; }
    public UUID getTargetId() { return targetId; }
    public AgentAuditEventType getEventType() { return eventType; }
    public String getResult() { return result; }
    public String getRequestId() { return requestId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
