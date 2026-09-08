package com.agentforge.core.conversation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDetailView(UUID conversationId, String preview, int messageCount,
        Instant createdAt, Instant updatedAt, List<ConversationMessageView> messages) { }
