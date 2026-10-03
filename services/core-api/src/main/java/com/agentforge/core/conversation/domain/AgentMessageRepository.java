package com.agentforge.core.conversation.domain;

import java.util.List;
import java.util.UUID;

public interface AgentMessageRepository {
    List<AgentMessage> saveAll(Iterable<AgentMessage> messages);
    List<AgentMessage> findAllByConversationId(UUID conversationId);
    List<AgentMessage> findAllByConversationIdAndRequestId(UUID conversationId, String requestId);
    void deleteAllByConversationId(UUID conversationId);
}
