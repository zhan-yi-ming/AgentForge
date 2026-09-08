package com.agentforge.core.conversation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentConversationRepository {
    Optional<AgentConversation> findByIdForUpdate(UUID id);
    Optional<AgentConversation> findByScope(UUID id, UUID projectId, UUID userId);
    List<AgentConversation> findAllByScope(UUID projectId, UUID userId);
    AgentConversation save(AgentConversation conversation);
}
