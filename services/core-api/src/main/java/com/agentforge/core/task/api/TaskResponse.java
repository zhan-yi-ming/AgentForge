package com.agentforge.core.task.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

public record TaskResponse(
        UUID id,
        UUID projectId,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskResponse from(TaskView task) {
        return new TaskResponse(
                task.id(),
                task.projectId(),
                task.title(),
                task.description(),
                task.status(),
                task.priority(),
                task.version(),
                task.createdAt(),
                task.updatedAt());
    }
}
