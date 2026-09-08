package com.agentforge.core.agent.application;

import java.util.UUID;

public record AgentResumeResult(
        UUID conversationId,
        UUID actionId,
        String decision,
        String status,
        String requestId) {
}
