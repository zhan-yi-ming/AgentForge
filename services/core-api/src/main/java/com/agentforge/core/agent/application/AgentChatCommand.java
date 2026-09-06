package com.agentforge.core.agent.application;

import java.util.UUID;

import com.agentforge.core.security.AuthenticatedActor;

public record AgentChatCommand(
        UUID projectId,
        AuthenticatedActor actor,
        String message,
        UUID conversationId,
        String requestId) {
}
