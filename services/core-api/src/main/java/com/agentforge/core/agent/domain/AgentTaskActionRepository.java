package com.agentforge.core.agent.domain;

import java.util.Optional;
import java.util.UUID;

public interface AgentTaskActionRepository {

    AgentTaskAction save(AgentTaskAction action);

    Optional<AgentTaskAction> findByProjectIdAndIdForUpdate(UUID projectId, UUID id);
}
