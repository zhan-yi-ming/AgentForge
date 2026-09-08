package com.agentforge.core.agent.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.agentforge.core.agent.domain.AgentAuditEvent;
import com.agentforge.core.agent.domain.AgentAuditEventRepository;

@Repository
class AgentAuditEventRepositoryAdapter implements AgentAuditEventRepository {

    private final SpringDataAgentAuditEventRepository repository;

    AgentAuditEventRepositoryAdapter(SpringDataAgentAuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public AgentAuditEvent save(AgentAuditEvent event) {
        return repository.save(event);
    }
}

interface SpringDataAgentAuditEventRepository extends JpaRepository<AgentAuditEvent, UUID> {
}
