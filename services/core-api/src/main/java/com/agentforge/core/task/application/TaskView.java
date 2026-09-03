package com.agentforge.core.task.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.task.domain.TaskItem;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

public record TaskView(
        UUID id,
        UUID projectId,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static TaskView from(TaskItem task) {
        return new TaskView(
                task.getId(),
                task.getProjectId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getVersion(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
