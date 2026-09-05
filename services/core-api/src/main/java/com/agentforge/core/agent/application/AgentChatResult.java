package com.agentforge.core.agent.application;

import java.util.List;
import java.util.UUID;

public record AgentChatResult(
        UUID conversationId,
        String answer,
        String requestId,
        List<AgentSource> sources) {

    public AgentChatResult(UUID conversationId, String answer, String requestId) {
        this(conversationId, answer, requestId, List.of());
    }

    public AgentChatResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
