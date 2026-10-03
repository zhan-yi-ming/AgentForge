package com.agentforge.core.agent.api;

import com.agentforge.core.agent.application.RecoverableAgentActionView;
import com.agentforge.core.agent.domain.AgentActionSource;

public record RecoverableAgentActionResponse(
        AgentActionResponse action,
        AgentActionSource source,
        String decisionKey) {

    static RecoverableAgentActionResponse from(RecoverableAgentActionView view) {
        return new RecoverableAgentActionResponse(
                AgentActionResponse.from(view.action()),
                view.source(),
                view.decisionKey());
    }
}
