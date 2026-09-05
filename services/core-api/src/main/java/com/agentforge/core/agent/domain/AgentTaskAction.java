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

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

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

    protected AgentTaskAction() {
    }

    private AgentTaskAction(
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
        this.id = UUID.randomUUID();
        this.projectId = Objects.requireNonNull(projectId);
        this.requestedByUserId = Objects.requireNonNull(requestedByUserId);
        this.conversationId = Objects.requireNonNull(conversationId);
        this.actionType = Objects.requireNonNull(actionType);
        this.taskId = taskId;
        this.title = title;
        this.description = description;
        this.taskStatus = taskStatus;
        this.priority = priority;
        this.expectedTaskVersion = expectedTaskVersion;
        this.status = AgentActionStatus.PENDING;
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
                conversationId,
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

    public void reject(Instant decidedAt) {
        this.status = AgentActionStatus.REJECTED;
        this.decidedAt = Objects.requireNonNull(decidedAt);
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public UUID getConversationId() { return conversationId; }
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
}
