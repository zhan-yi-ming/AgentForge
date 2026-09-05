package com.agentforge.core.agent.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.agent.domain.AgentTaskActionRepository;

@Repository
class AgentTaskActionRepositoryAdapter implements AgentTaskActionRepository {

    private final SpringDataAgentTaskActionRepository repository;

    AgentTaskActionRepositoryAdapter(SpringDataAgentTaskActionRepository repository) {
        this.repository = repository;
    }

    @Override
    public AgentTaskAction save(AgentTaskAction action) {
        return repository.saveAndFlush(action);
    }

    @Override
    public Optional<AgentTaskAction> findByProjectIdAndIdForUpdate(UUID projectId, UUID id) {
        return repository.findByProjectIdAndId(projectId, id);
    }
}

interface SpringDataAgentTaskActionRepository extends JpaRepository<AgentTaskAction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentTaskAction> findByProjectIdAndId(UUID projectId, UUID id);
}
