package com.agentforge.core.agent.application;

import java.util.List;
import java.util.UUID;

public record AgentStreamEvent(
        String type,
        UUID conversationId,
        String requestId,
        List<AgentSource> sources,
        String text,
        ToolProposal toolProposal,
        AgentActionView pendingAction,
        String message) {

    public AgentStreamEvent {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static AgentStreamEvent metadata(
            UUID conversationId, String requestId, List<AgentSource> sources) {
        return new AgentStreamEvent(
                "metadata", conversationId, requestId, sources, null, null, null, null);
    }

    public static AgentStreamEvent delta(String text) {
        return new AgentStreamEvent("delta", null, null, List.of(), text, null, null, null);
    }

    public static AgentStreamEvent complete(ToolProposal proposal) {
        return new AgentStreamEvent("complete", null, null, List.of(), null, proposal, null, null);
    }

    public static AgentStreamEvent completed(AgentActionView action) {
        return new AgentStreamEvent("complete", null, null, List.of(), null, null, action, null);
    }
}
