package com.agentforge.core.project.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.project.application.ProjectView;

public record ProjectResponse(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    static ProjectResponse from(ProjectView project) {
        return new ProjectResponse(
                project.id(),
                project.ownerId(),
                project.name(),
                project.description(),
                project.createdAt(),
                project.updatedAt());
    }
}
