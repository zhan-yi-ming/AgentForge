package com.agentforge.core.agent.api;

import java.util.UUID;

import com.agentforge.core.agent.application.AgentChatResult;

public record AgentChatResponse(UUID conversationId, String answer, String requestId) {

    static AgentChatResponse from(AgentChatResult result) {
        return new AgentChatResponse(result.conversationId(), result.answer(), result.requestId());
    }
}
