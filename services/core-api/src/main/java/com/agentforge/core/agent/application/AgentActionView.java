package com.agentforge.core.agent.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.task.application.TaskView;

public record AgentActionView(
        UUID id,
        UUID projectId,
        UUID conversationId,
        AgentActionType actionType,
        AgentActionStatus status,
        UUID taskId,
        Long expectedVersion,
        String title,
        String description,
        String taskStatus,
        String priority,
        TaskView resultTask,
        Instant createdAt,
        Instant decidedAt) {

    static AgentActionView from(AgentTaskAction action, TaskView resultTask) {
        return new AgentActionView(
                action.getId(),
                action.getProjectId(),
                action.getConversationId(),
                action.getActionType(),
                action.getStatus(),
                action.getTaskId(),
                action.getExpectedTaskVersion(),
                action.getTitle(),
                action.getDescription(),
                action.getTaskStatus() == null ? null : action.getTaskStatus().name(),
                action.getPriority() == null ? null : action.getPriority().name(),
                resultTask,
                action.getCreatedAt(),
                action.getDecidedAt());
    }
}
