package com.agentforge.core.agent.application;

import java.util.UUID;

public interface AgentServiceClient {

    AgentChatResult chat(UUID projectId, UUID userId, String message, UUID conversationId, String requestId);
}
