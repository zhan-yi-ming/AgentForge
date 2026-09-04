package com.agentforge.core.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.task.domain.TaskItem;
import com.agentforge.core.task.domain.TaskItemRepository;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private TaskItemRepository tasks;

    @Mock
    private ProjectAccess projectAccess;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(tasks, projectAccess, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createAppliesDocumentedDefaults() {
        UUID projectId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        when(tasks.save(any(TaskItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskView result = service.create(projectId, actor, "  First task  ", "  details  ", null, null);

        assertThat(result.title()).isEqualTo("First task");
        assertThat(result.description()).isEqualTo("details");
        assertThat(result.status()).isEqualTo(TaskStatus.TODO);
        assertThat(result.priority()).isEqualTo(TaskPriority.MEDIUM);
        verify(projectAccess).requireAccess(projectId, actor);
    }

    @Test
    void updateRejectsStaleVersion() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskItem task = TaskItem.create(
                projectId,
                "Task",
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                NOW);
        when(tasks.findByProjectIdAndId(projectId, taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.update(
                projectId,
                taskId,
                new AuthenticatedActor(UUID.randomUUID(), true),
                "Task",
                null,
                TaskStatus.DONE,
                TaskPriority.HIGH,
                1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void updateAndDeleteUseCurrentVersion() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        TaskItem task = TaskItem.create(
                projectId,
                "Task",
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                NOW);
        when(tasks.findByProjectIdAndId(projectId, taskId)).thenReturn(Optional.of(task));
        when(tasks.save(task)).thenReturn(task);

        TaskView updated = service.update(
                projectId,
                taskId,
                actor,
                " Updated task ",
                " done ",
                TaskStatus.DONE,
                TaskPriority.HIGH,
                0);

        assertThat(updated.title()).isEqualTo("Updated task");
        assertThat(updated.description()).isEqualTo("done");
        assertThat(updated.status()).isEqualTo(TaskStatus.DONE);
        service.delete(projectId, taskId, actor, updated.version());
        verify(tasks).delete(task);
        verify(projectAccess, times(2)).requireAccess(projectId, actor);
    }

    @Test
    void deleteRejectsStaleVersionWithoutDeleting() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        TaskItem task = TaskItem.create(projectId, "Task", null, TaskStatus.TODO, TaskPriority.MEDIUM, NOW);
        when(tasks.findByProjectIdAndId(projectId, taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.delete(projectId, taskId, actor, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("stale");
        verify(tasks, never()).delete(task);
    }

    @Test
    void projectAuthorizationRunsBeforeTaskLookup() {
        UUID projectId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        org.mockito.Mockito.doThrow(new ForbiddenException("forbidden"))
                .when(projectAccess).requireAccess(projectId, actor);

        assertThatThrownBy(() -> service.get(projectId, UUID.randomUUID(), actor))
                .isInstanceOf(ForbiddenException.class);
        verify(tasks, never()).findByProjectIdAndId(any(), any());
    }
}
