package com.agentforge.core.agent.application;

import java.util.UUID;
import java.util.function.Consumer;

public interface AgentServiceClient {

    AgentChatResult chat(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            String message,
            UUID conversationId,
            String requestId);

    void stream(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            String message,
            UUID conversationId,
            String requestId,
            Consumer<AgentStreamEvent> sink);

    default AgentChatResult chat(UUID projectId, UUID userId, boolean actorAdmin, String message,
            UUID conversationId, String requestId, AgentTaskType taskType) {
        if (taskType != AgentTaskType.ANSWER) throw new UnsupportedOperationException("Task mode unsupported");
        return chat(projectId, userId, actorAdmin, message, conversationId, requestId);
    }

    default void stream(UUID projectId, UUID userId, boolean actorAdmin, String message,
            UUID conversationId, String requestId, AgentTaskType taskType, Consumer<AgentStreamEvent> sink) {
        if (taskType != AgentTaskType.ANSWER) throw new UnsupportedOperationException("Task mode unsupported");
        stream(projectId, userId, actorAdmin, message, conversationId, requestId, sink);
    }

    AgentResumeResult resume(
            UUID projectId,
            UUID checkpointUserId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionWorkflowId,
            UUID actionId,
            String decision,
            String idempotencyKey,
            String requestId);

    AgentAbortResult abort(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionWorkflowId,
            String requestId);

    default AgentResumeResult resume(
            UUID projectId,
            UUID checkpointUserId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionId,
            String decision,
            String idempotencyKey,
            String requestId) {
        return resume(
                projectId, checkpointUserId, actorAdmin, conversationId, null, actionId,
                decision, idempotencyKey, requestId);
    }
}
