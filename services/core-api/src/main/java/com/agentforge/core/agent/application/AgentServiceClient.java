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
}
