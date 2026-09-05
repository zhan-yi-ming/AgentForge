package com.agentforge.core.rag.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RagSourcesRequest(
        @NotNull UUID projectId,
        @NotNull UUID userId,
        boolean actorAdmin,
        @NotBlank @Size(max = 128) String requestId) {
}
