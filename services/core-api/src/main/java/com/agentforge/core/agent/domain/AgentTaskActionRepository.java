package com.agentforge.core.agent.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AgentTaskActionRepository {

    AgentTaskAction save(AgentTaskAction action);

    Optional<AgentTaskAction> findByProjectIdAndIdForUpdate(UUID projectId, UUID id);

    void lockMcpProposal(UUID projectId, UUID userId, String proposalIdempotencyKey);

    Optional<AgentTaskAction> findMcpProposal(
            UUID projectId,
            UUID userId,
            String proposalIdempotencyKey);
    boolean existsByConversationAndStatusIn(UUID projectId, UUID userId, UUID conversationId,
            List<AgentActionStatus> statuses);
}
