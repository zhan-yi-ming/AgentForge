package com.agentforge.core.project.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.project.domain.Project;

public record ProjectView(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    static ProjectView from(Project project) {
        return new ProjectView(
                project.getId(),
                project.getOwnerId(),
                project.getName(),
                project.getDescription(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }
}
