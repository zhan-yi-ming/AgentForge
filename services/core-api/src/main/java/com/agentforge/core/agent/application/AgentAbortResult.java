package com.agentforge.core.agent.application;

import java.util.UUID;

public record AgentAbortResult(
        UUID conversationId,
        UUID actionWorkflowId,
        String status,
        String requestId) {
}
