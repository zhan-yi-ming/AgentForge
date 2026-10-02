package com.agentforge.core.agent.application;

import java.util.UUID;

public record AgentResumeResult(
        UUID conversationId,
        UUID actionWorkflowId,
        UUID actionId,
        String decision,
        String status,
        String requestId) {

    public AgentResumeResult(
            UUID conversationId,
            UUID actionId,
            String decision,
            String status,
            String requestId) {
        this(conversationId, null, actionId, decision, status, requestId);
    }
}
