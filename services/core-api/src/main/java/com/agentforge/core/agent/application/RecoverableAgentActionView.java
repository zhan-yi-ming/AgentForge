package com.agentforge.core.agent.application;

import com.agentforge.core.agent.domain.AgentActionSource;

public record RecoverableAgentActionView(
        AgentActionView action,
        AgentActionSource source,
        String decisionKey) {
}
