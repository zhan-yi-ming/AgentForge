package com.agentforge.core.task.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.task.domain.TaskItem;
import com.agentforge.core.task.domain.TaskItemRepository;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@Service
public class TaskService {

    private final TaskItemRepository tasks;
    private final ProjectAccess projectAccess;
    private final Clock clock;

    public TaskService(TaskItemRepository tasks, ProjectAccess projectAccess, Clock clock) {
        this.tasks = tasks;
        this.projectAccess = projectAccess;
        this.clock = clock;
    }

    @Transactional
    public TaskView create(
            UUID projectId,
            AuthenticatedActor actor,
            String title,
            String description,
            TaskStatus status,
            TaskPriority priority) {
        projectAccess.requireAccess(projectId, actor);
        TaskItem task = TaskItem.create(
                projectId,
                title.trim(),
                normalizeDescription(description),
                status == null ? TaskStatus.TODO : status,
                priority == null ? TaskPriority.MEDIUM : priority,
                Instant.now(clock));
        return TaskView.from(tasks.save(task));
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID projectId, UUID taskId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        return TaskView.from(find(projectId, taskId));
    }

    @Transactional(readOnly = true)
    public List<TaskView> list(UUID projectId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        return tasks.findAllByProjectIdOrderByUpdatedAtDesc(projectId)
                .stream()
                .map(TaskView::from)
                .toList();
    }

    @Transactional
    public TaskView update(
            UUID projectId,
            UUID taskId,
            AuthenticatedActor actor,
            String title,
            String description,
            TaskStatus status,
            TaskPriority priority,
            long expectedVersion) {
        projectAccess.requireAccess(projectId, actor);
        TaskItem task = find(projectId, taskId);
        requireVersion(task, expectedVersion);
        task.update(
                title.trim(),
                normalizeDescription(description),
                status,
                priority,
                Instant.now(clock));
        try {
            return TaskView.from(tasks.save(task));
        }
        catch (OptimisticLockingFailureException exception) {
            throw new ConflictException("The Task was changed by another request.");
        }
    }

    @Transactional
    public void delete(UUID projectId, UUID taskId, AuthenticatedActor actor, long expectedVersion) {
        projectAccess.requireAccess(projectId, actor);
        TaskItem task = find(projectId, taskId);
        requireVersion(task, expectedVersion);
        try {
            tasks.delete(task);
        }
        catch (OptimisticLockingFailureException exception) {
            throw new ConflictException("The Task was changed by another request.");
        }
    }

    private TaskItem find(UUID projectId, UUID taskId) {
        return tasks.findByProjectIdAndId(projectId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
    }

    private void requireVersion(TaskItem task, long expectedVersion) {
        if (task.getVersion() != expectedVersion) {
            throw new ConflictException("The Task version is stale.");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
