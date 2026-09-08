package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentAuditEvent;
import com.agentforge.core.agent.domain.AgentAuditEventRepository;
import com.agentforge.core.agent.domain.AgentAuditEventType;
import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.agent.domain.AgentTaskActionRepository;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.ToolOperation;
import com.agentforge.core.security.ToolRiskEngine;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

class AgentActionServiceTest {

    private final ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
    private final AgentTaskActionRepository actions = org.mockito.Mockito.mock(AgentTaskActionRepository.class);
    private final AgentAuditEventRepository auditEvents = org.mockito.Mockito.mock(AgentAuditEventRepository.class);
    private final TaskService taskService = org.mockito.Mockito.mock(TaskService.class);
    private final ToolRiskEngine riskEngine = org.mockito.Mockito.mock(ToolRiskEngine.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
    private AgentActionService service;

    @BeforeEach
    void setUp() {
        service = new AgentActionService(actions, auditEvents, projectAccess, riskEngine, taskService, clock);
        when(actions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditEvents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createProposalStaysPendingUntilConfirmation() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var actor = new AuthenticatedActor(userId, false);
        var proposal = new ToolProposal("CREATE_TASK", null, null, "Add login", "JWT", "TODO", "HIGH");

        AgentActionView pending = service.createPending(projectId, actor, UUID.randomUUID(), proposal).orElseThrow();

        assertThat(pending.status()).isEqualTo(AgentActionStatus.PENDING);
        verify(taskService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirmingCreateExecutesOnceAndReturnsSameTaskOnRetry() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var actor = new AuthenticatedActor(userId, false);
        AgentTaskAction action = AgentTaskAction.pending(
                projectId,
                userId,
                UUID.randomUUID(),
                com.agentforge.core.agent.domain.AgentActionType.CREATE_TASK,
                null,
                "Add login",
                "JWT",
                TaskStatus.TODO,
                TaskPriority.HIGH,
                null,
                Instant.now(clock));
        UUID taskId = UUID.randomUUID();
        TaskView task = task(taskId, projectId, "Add login", 0);
        when(actions.findByProjectIdAndIdForUpdate(projectId, action.getId())).thenReturn(Optional.of(action));
        when(taskService.create(projectId, actor, "Add login", "JWT", TaskStatus.TODO, TaskPriority.HIGH))
                .thenReturn(task);
        when(taskService.get(projectId, taskId, actor)).thenReturn(task);

        AgentActionView first = service.confirm(projectId, action.getId(), actor, "confirm-key-1", "request-1");
        AgentActionView repeated = service.confirm(projectId, action.getId(), actor, "confirm-key-1", "request-2");

        assertThat(first.status()).isEqualTo(AgentActionStatus.EXECUTED);
        assertThat(repeated.resultTask().id()).isEqualTo(taskId);
        verify(taskService).create(projectId, actor, "Add login", "JWT", TaskStatus.TODO, TaskPriority.HIGH);
        verify(auditEvents).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getEventType() == AgentAuditEventType.APPROVED
                        && event.getRequestId().equals("request-1")));
        verify(auditEvents).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getEventType() == AgentAuditEventType.EXECUTED
                        && event.getIdempotencyKey().equals("confirm-key-1")));
        assertThatThrownBy(() -> service.confirm(
                projectId, action.getId(), actor, "different-key", "request-3"))
                .isInstanceOf(ConflictException.class);
        verify(riskEngine, times(3)).authorize(ToolOperation.CREATE_TASK, projectId, actor);
    }

    @Test
    void rejectingActionNeverWritesAndCannotThenBeConfirmed() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var actor = new AuthenticatedActor(userId, false);
        AgentTaskAction action = AgentTaskAction.pending(
                projectId,
                userId,
                UUID.randomUUID(),
                com.agentforge.core.agent.domain.AgentActionType.CREATE_TASK,
                null,
                "Add login",
                null,
                TaskStatus.TODO,
                TaskPriority.MEDIUM,
                null,
                Instant.now(clock));
        when(actions.findByProjectIdAndIdForUpdate(projectId, action.getId())).thenReturn(Optional.of(action));

        assertThat(service.reject(
                projectId, action.getId(), actor, "reject-key", "reject-request").status())
                .isEqualTo(AgentActionStatus.REJECTED);
        assertThat(service.reject(
                projectId, action.getId(), actor, "reject-key", "reject-replay").status())
                .isEqualTo(AgentActionStatus.REJECTED);
        assertThatThrownBy(() -> service.reject(
                projectId, action.getId(), actor, "another-reject-key", "reject-conflict"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirm(projectId, action.getId(), actor))
                .isInstanceOf(ConflictException.class);
        verify(taskService, never()).create(any(), any(), any(), any(), any(), any());
        verify(auditEvents).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getEventType() == AgentAuditEventType.REJECTED
                        && event.getRequestId().equals("reject-request")));
        verify(riskEngine, times(4)).authorize(ToolOperation.CREATE_TASK, projectId, actor);
    }

    @Test
    void updateProposalRequiresCurrentVersionAndMergesOnlyProposedFields() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var actor = new AuthenticatedActor(userId, false);
        TaskView current = task(taskId, projectId, "Existing title", 2);
        when(taskService.get(projectId, taskId, actor)).thenReturn(current);
        var proposal = new ToolProposal("UPDATE_TASK", taskId, 2L, null, null, "DONE", null);

        AgentActionView pending = service.createPending(projectId, actor, UUID.randomUUID(), proposal).orElseThrow();
        AgentTaskAction action = org.mockito.Mockito.mockingDetails(actions).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("save"))
                .map(invocation -> (AgentTaskAction) invocation.getArgument(0))
                .findFirst()
                .orElseThrow();
        when(actions.findByProjectIdAndIdForUpdate(projectId, action.getId())).thenReturn(Optional.of(action));
        TaskView updated = new TaskView(
                taskId, projectId, "Existing title", "JWT", TaskStatus.DONE, TaskPriority.HIGH, 3,
                Instant.now(clock), Instant.now(clock));
        when(taskService.update(
                projectId, taskId, actor, "Existing title", "JWT", TaskStatus.DONE, TaskPriority.HIGH, 2))
                .thenReturn(updated);

        AgentActionView executed = service.confirm(projectId, pending.id(), actor);

        assertThat(executed.resultTask().status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void anotherUserCannotDecidePendingAction() {
        UUID projectId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AgentTaskAction action = AgentTaskAction.pending(
                projectId, ownerId, UUID.randomUUID(),
                com.agentforge.core.agent.domain.AgentActionType.CREATE_TASK,
                null, "Add login", null, TaskStatus.TODO, TaskPriority.MEDIUM, null, Instant.now(clock));
        when(actions.findByProjectIdAndIdForUpdate(projectId, action.getId())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.reject(
                projectId, action.getId(), new AuthenticatedActor(UUID.randomUUID(), false)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void invalidProposalIsIgnoredWithoutSavingAction() {
        UUID projectId = UUID.randomUUID();
        var actor = new AuthenticatedActor(UUID.randomUUID(), false);
        var proposal = new ToolProposal("CREATE_TASK", null, null, "x".repeat(201), null, "TODO", "HIGH");

        assertThat(service.createPending(projectId, actor, UUID.randomUUID(), proposal)).isEmpty();
        verify(actions, never()).save(any());
    }

    @Test
    void staleUpdateProposalIsRejectedBeforeSavingAction() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var actor = new AuthenticatedActor(UUID.randomUUID(), false);
        when(taskService.get(projectId, taskId, actor)).thenReturn(task(taskId, projectId, "Existing title", 3));
        var proposal = new ToolProposal("UPDATE_TASK", taskId, 2L, null, null, "DONE", null);

        assertThatThrownBy(() -> service.createPending(projectId, actor, UUID.randomUUID(), proposal))
                .isInstanceOf(ConflictException.class);
        verify(actions, never()).save(any());
    }

    @Test
    void concurrentVersionConflictMarksTheApprovedActionFailed() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var actor = new AuthenticatedActor(UUID.randomUUID(), false);
        AgentTaskAction action = AgentTaskAction.pending(
                projectId, actor.userId(), UUID.randomUUID(),
                com.agentforge.core.agent.domain.AgentActionType.UPDATE_TASK,
                taskId, null, null, TaskStatus.DONE, null, 2L, Instant.now(clock));
        when(actions.findByProjectIdAndIdForUpdate(projectId, action.getId())).thenReturn(Optional.of(action));
        when(taskService.get(projectId, taskId, actor)).thenReturn(task(taskId, projectId, "Existing title", 3));
        when(taskService.update(
                projectId, taskId, actor, "Existing title", "JWT", TaskStatus.DONE, TaskPriority.HIGH, 2))
                .thenThrow(new ConflictException("The Task version is stale."));

        AgentActionView failed = service.confirm(
                projectId, action.getId(), actor, "failed-key", "failed-request");
        AgentActionView replayed = service.confirm(
                projectId, action.getId(), actor, "failed-key", "replay-request");

        assertThat(failed.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(replayed.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(action.getStatus()).isEqualTo(AgentActionStatus.FAILED);
        verify(auditEvents).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getEventType() == AgentAuditEventType.FAILED
                        && event.getRequestId().equals("failed-request")));
        verify(taskService).update(
                projectId, taskId, actor, "Existing title", "JWT", TaskStatus.DONE, TaskPriority.HIGH, 2);
    }

    @Test
    void executedReplayKeepsTerminalFactWhenResultTaskWasDeletedLater() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var actor = new AuthenticatedActor(UUID.randomUUID(), false);
        AgentTaskAction action = AgentTaskAction.pending(
                projectId, actor.userId(), UUID.randomUUID(),
                com.agentforge.core.agent.domain.AgentActionType.CREATE_TASK,
                null, "Created then deleted", null, TaskStatus.TODO, TaskPriority.MEDIUM, null, Instant.now(clock));
        action.approve("deleted-result-key", Instant.now(clock));
        action.markExecuted(taskId, Instant.now(clock));
        when(actions.findByProjectIdAndIdForUpdate(projectId, action.getId())).thenReturn(Optional.of(action));
        when(taskService.get(projectId, taskId, actor))
                .thenThrow(new ResourceNotFoundException("Task not found: " + taskId));

        AgentActionView replayed = service.confirm(
                projectId, action.getId(), actor, "deleted-result-key", "deleted-result-replay");

        assertThat(replayed.status()).isEqualTo(AgentActionStatus.EXECUTED);
        assertThat(replayed.resultTask()).isNull();
        verify(taskService, never()).create(any(), any(), any(), any(), any(), any());
    }

    private TaskView task(UUID id, UUID projectId, String title, long version) {
        return new TaskView(
                id,
                projectId,
                title,
                "JWT",
                TaskStatus.TODO,
                TaskPriority.HIGH,
                version,
                Instant.now(clock),
                Instant.now(clock));
    }
}
