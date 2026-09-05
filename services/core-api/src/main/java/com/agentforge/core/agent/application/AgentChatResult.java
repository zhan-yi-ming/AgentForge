package com.agentforge.core.agent.application;

import java.util.List;
import java.util.UUID;

public record AgentChatResult(
        UUID conversationId,
        String answer,
        String requestId,
        List<AgentSource> sources,
        ToolProposal toolProposal,
        AgentActionView pendingAction) {

    public AgentChatResult(UUID conversationId, String answer, String requestId) {
        this(conversationId, answer, requestId, List.of(), null, null);
    }

    public AgentChatResult(UUID conversationId, String answer, String requestId, List<AgentSource> sources) {
        this(conversationId, answer, requestId, sources, null, null);
    }

    public AgentChatResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    AgentChatResult withPendingAction(AgentActionView action) {
        return new AgentChatResult(conversationId, answer, requestId, sources, null, action);
    }

    AgentChatResult withoutToolProposal() {
        return new AgentChatResult(conversationId, answer, requestId, sources, null, null);
    }
}
