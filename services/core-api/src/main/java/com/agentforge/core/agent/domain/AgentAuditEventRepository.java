package com.agentforge.core.agent.domain;

public interface AgentAuditEventRepository {

    AgentAuditEvent save(AgentAuditEvent event);
}
