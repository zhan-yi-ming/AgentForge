package com.agentforge.core.agent.application;

import java.util.UUID;

import com.agentforge.core.security.AuthenticatedActor;

public record AgentChatCommand(
        UUID projectId,
        AuthenticatedActor actor,
        String message,
        UUID conversationId,
        String requestId,
        AgentTaskType taskType) {
    public AgentChatCommand {
        taskType = taskType == null ? AgentTaskType.ANSWER : taskType;
    }
    public AgentChatCommand(UUID projectId, AuthenticatedActor actor, String message,
            UUID conversationId, String requestId) {
        this(projectId, actor, message, conversationId, requestId, AgentTaskType.ANSWER);
    }
}
