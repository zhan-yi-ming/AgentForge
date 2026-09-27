package com.agentforge.core.agent.api;

import java.util.UUID;
import com.agentforge.core.agent.application.AgentTaskType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        @NotBlank @Size(max = 16000) String message,
        UUID conversationId,
        AgentTaskType taskType) {
    public AgentChatRequest {
        taskType = taskType == null ? AgentTaskType.ANSWER : taskType;
    }
    public AgentChatRequest(String message, UUID conversationId) {
        this(message, conversationId, AgentTaskType.ANSWER);
    }
}
