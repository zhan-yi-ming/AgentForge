package com.agentforge.core.agent.api;

import java.util.List;
import java.util.UUID;

import com.agentforge.core.agent.application.AgentChatResult;
import com.agentforge.core.agent.application.AgentSource;

public record AgentChatResponse(
        UUID conversationId,
        String answer,
        String requestId,
        List<AgentSource> sources,
        AgentActionResponse pendingAction) {

    static AgentChatResponse from(AgentChatResult result) {
        return new AgentChatResponse(
                result.conversationId(),
                result.answer(),
                result.requestId(),
                result.sources(),
                result.pendingAction() == null ? null : AgentActionResponse.from(result.pendingAction()));
    }
}
