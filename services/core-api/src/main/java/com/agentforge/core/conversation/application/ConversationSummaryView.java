package com.agentforge.core.conversation.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.conversation.domain.AgentConversation;

public record ConversationSummaryView(UUID conversationId, String preview, int messageCount,
        Instant createdAt, Instant updatedAt) {
    static ConversationSummaryView from(AgentConversation conversation) {
        return new ConversationSummaryView(conversation.getId(), conversation.getPreview(),
                conversation.getMessageCount(), conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
