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
import jakarta.persistence.Version;

import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@Entity
@Table(name = "agent_task_action")
public class AgentTaskAction {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentActionSource source;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "proposal_idempotency_key", length = 100)
    private String proposalIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 24)
    private AgentActionType actionType;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(length = 200)
    private String title;

    @Column(length = 10000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", length = 20)
    private TaskStatus taskStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskPriority priority;

    @Column(name = "expected_task_version")
    private Long expectedTaskVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentActionStatus status;

    @Column(name = "result_task_id")
    private UUID resultTaskId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "action_workflow_version")
    private Integer actionWorkflowVersion;

    protected AgentTaskAction() {
    }

    private AgentTaskAction(
            UUID projectId,
            UUID requestedByUserId,
            AgentActionSource source,
            UUID conversationId,
            String proposalIdempotencyKey,
            AgentActionType actionType,
            UUID taskId,
            String title,
            String description,
            TaskStatus taskStatus,
            TaskPriority priority,
            Long expectedTaskVersion,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.projectId = Objects.requireNonNull(projectId);
        this.requestedByUserId = Objects.requireNonNull(requestedByUserId);
        this.source = Objects.requireNonNull(source);
        this.conversationId = conversationId;
        this.proposalIdempotencyKey = proposalIdempotencyKey;
        if (source == AgentActionSource.CHAT && (conversationId == null || proposalIdempotencyKey != null)) {
            throw new IllegalArgumentException("Chat actions require a conversation.");
        }
        if (source == AgentActionSource.MCP
                && (conversationId != null || proposalIdempotencyKey == null)) {
            throw new IllegalArgumentException(
                    "MCP actions require a proposal idempotency key and cannot bind a conversation.");
        }
        this.actionType = Objects.requireNonNull(actionType);
        this.taskId = taskId;
        this.title = title;
        this.description = description;
        this.taskStatus = taskStatus;
        this.priority = priority;
        this.expectedTaskVersion = expectedTaskVersion;
        this.status = AgentActionStatus.PENDING;
        this.actionWorkflowVersion = source == AgentActionSource.CHAT ? 1 : null;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static AgentTaskAction pending(
            UUID projectId,
            UUID requestedByUserId,
            UUID conversationId,
            AgentActionType actionType,
            UUID taskId,
            String title,
            String description,
            TaskStatus taskStatus,
            TaskPriority priority,
            Long expectedTaskVersion,
            Instant createdAt) {
        return new AgentTaskAction(
                projectId,
                requestedByUserId,
                AgentActionSource.CHAT,
                conversationId,
                null,
                actionType,
                taskId,
                title,
                description,
                taskStatus,
                priority,
                expectedTaskVersion,
                createdAt);
    }

    public static AgentTaskAction pendingMcp(
            UUID projectId,
            UUID requestedByUserId,
            String proposalIdempotencyKey,
            AgentActionType actionType,
            UUID taskId,
            String title,
            String description,
            TaskStatus taskStatus,
            TaskPriority priority,
            Long expectedTaskVersion,
            Instant createdAt) {
        return new AgentTaskAction(
                projectId,
                requestedByUserId,
                AgentActionSource.MCP,
                null,
                Objects.requireNonNull(proposalIdempotencyKey),
                actionType,
                taskId,
                title,
                description,
                taskStatus,
                priority,
                expectedTaskVersion,
                createdAt);
    }

    public void markExecuted(UUID resultTaskId, Instant decidedAt) {
        this.resultTaskId = Objects.requireNonNull(resultTaskId);
        this.status = AgentActionStatus.EXECUTED;
        this.decidedAt = Objects.requireNonNull(decidedAt);
    }

    public void markFailed(Instant decidedAt) {
        if (status != AgentActionStatus.APPROVED) {
            throw new IllegalStateException("Only approved actions can fail execution.");
        }
        this.status = AgentActionStatus.FAILED;
        this.decidedAt = Objects.requireNonNull(decidedAt);
    }

    public void approve(String idempotencyKey, Instant approvedAt) {
        if (status != AgentActionStatus.PENDING) {
            throw new IllegalStateException("Only pending actions can be approved.");
        }
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.status = AgentActionStatus.APPROVED;
        this.approvedAt = Objects.requireNonNull(approvedAt);
    }

    public boolean hasIdempotencyKey(String candidate) {
        return Objects.equals(idempotencyKey, candidate);
    }

    public void reject(String idempotencyKey, Instant decidedAt) {
        if (status != AgentActionStatus.PENDING) {
            throw new IllegalStateException("Only pending actions can be rejected.");
        }
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.status = AgentActionStatus.REJECTED;
        this.decidedAt = Objects.requireNonNull(decidedAt);
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public AgentActionSource getSource() { return source; }
    public UUID getConversationId() { return conversationId; }
    public String getProposalIdempotencyKey() { return proposalIdempotencyKey; }
    public AgentActionType getActionType() { return actionType; }
    public UUID getTaskId() { return taskId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TaskStatus getTaskStatus() { return taskStatus; }
    public TaskPriority getPriority() { return priority; }
    public Long getExpectedTaskVersion() { return expectedTaskVersion; }
    public AgentActionStatus getStatus() { return status; }
    public UUID getResultTaskId() { return resultTaskId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getApprovedAt() { return approvedAt; }
    public Integer getActionWorkflowVersion() { return actionWorkflowVersion; }
}
