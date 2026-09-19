package com.agentforge.core.agent.infrastructure;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.agent.domain.AgentTaskActionRepository;
import com.agentforge.core.agent.domain.AgentActionStatus;

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

    @Override
    public boolean existsByConversationAndStatusIn(UUID projectId, UUID userId, UUID conversationId,
            List<AgentActionStatus> statuses) {
        return repository.existsByProjectIdAndRequestedByUserIdAndConversationIdAndStatusIn(
                projectId, userId, conversationId, statuses);
    }
}

interface SpringDataAgentTaskActionRepository extends JpaRepository<AgentTaskAction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentTaskAction> findByProjectIdAndId(UUID projectId, UUID id);
    boolean existsByProjectIdAndRequestedByUserIdAndConversationIdAndStatusIn(
            UUID projectId, UUID userId, UUID conversationId, List<AgentActionStatus> statuses);
}
