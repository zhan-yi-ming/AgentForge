package com.agentforge.core.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

public record CreateTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 10000) String description,
        TaskStatus status,
        TaskPriority priority) {
}
