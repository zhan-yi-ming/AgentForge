package com.agentforge.core.conversation.application;

import java.time.Instant;
import java.util.List;

import com.agentforge.core.agent.application.AgentSource;
import com.agentforge.core.conversation.domain.AgentMessageRole;

public record ConversationMessageView(AgentMessageRole role, String content, List<AgentSource> sources,
        Instant createdAt) { }
