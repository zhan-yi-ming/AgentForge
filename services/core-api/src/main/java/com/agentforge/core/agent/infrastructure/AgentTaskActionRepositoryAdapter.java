package com.agentforge.core.agent.infrastructure;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.agent.domain.AgentTaskActionRepository;
import com.agentforge.core.agent.domain.AgentActionSource;
import com.agentforge.core.agent.domain.AgentActionStatus;

@Repository
class AgentTaskActionRepositoryAdapter implements AgentTaskActionRepository {

    private final SpringDataAgentTaskActionRepository repository;
    private final EntityManager entityManager;

    AgentTaskActionRepositoryAdapter(
            SpringDataAgentTaskActionRepository repository,
            EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
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
    public void lockMcpProposal(UUID projectId, UUID userId, String proposalIdempotencyKey) {
        String lockKey = projectId + ":" + userId + ":" + proposalIdempotencyKey;
        entityManager.createNativeQuery(
                        "select pg_advisory_xact_lock(hashtextextended(cast(?1 as text), 0))")
                .setParameter(1, lockKey)
                .getSingleResult();
    }

    @Override
    public Optional<AgentTaskAction> findMcpProposal(
            UUID projectId,
            UUID userId,
            String proposalIdempotencyKey) {
        return repository.findByProjectIdAndRequestedByUserIdAndSourceAndProposalIdempotencyKey(
                projectId,
                userId,
                AgentActionSource.MCP,
                proposalIdempotencyKey);
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

    Optional<AgentTaskAction> findByProjectIdAndRequestedByUserIdAndSourceAndProposalIdempotencyKey(
            UUID projectId,
            UUID userId,
            AgentActionSource source,
            String proposalIdempotencyKey);
    boolean existsByProjectIdAndRequestedByUserIdAndConversationIdAndStatusIn(
            UUID projectId, UUID userId, UUID conversationId, List<AgentActionStatus> statuses);
}
