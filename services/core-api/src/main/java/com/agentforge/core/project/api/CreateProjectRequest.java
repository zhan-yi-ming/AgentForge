package com.agentforge.core.project.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description) {
}
