package com.agentforge.core.agent.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.agent.application.AgentActionView;
import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.task.api.TaskResponse;

public record AgentActionResponse(
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
        TaskResponse resultTask,
        Instant createdAt,
        Instant decidedAt) {

    static AgentActionResponse from(AgentActionView action) {
        return new AgentActionResponse(
                action.id(),
                action.projectId(),
                action.conversationId(),
                action.actionType(),
                action.status(),
                action.taskId(),
                action.expectedVersion(),
                action.title(),
                action.description(),
                action.taskStatus(),
                action.priority(),
                action.resultTask() == null ? null : TaskResponse.from(action.resultTask()),
                action.createdAt(),
                action.decidedAt());
    }
}
