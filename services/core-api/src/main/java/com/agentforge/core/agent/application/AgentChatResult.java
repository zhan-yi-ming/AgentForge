package com.agentforge.core.agent.application;

import java.util.UUID;

public record AgentChatResult(UUID conversationId, String answer, String requestId) {
}
