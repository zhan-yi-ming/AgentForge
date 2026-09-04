package com.agentforge.core.agent.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        @NotBlank @Size(max = 8000) String message,
        UUID conversationId) {
}
