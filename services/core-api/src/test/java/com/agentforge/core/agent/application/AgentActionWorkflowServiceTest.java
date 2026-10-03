package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ServiceUnavailableException;

class AgentActionWorkflowServiceTest {

    @Test
    void adminConfirmationResumesTheRequestersCheckpointAndExecutesAsTheAdmin() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor admin = new AuthenticatedActor(adminId, true);
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        AgentActionView approved = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.APPROVED, null, null, "Admin resume", null,
                "TODO", "HIGH", null, now, now, 2, workflowId, requesterId);
        AgentActionView executed = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.EXECUTED, null, null, "Admin resume", null,
                "TODO", "HIGH", null, now, now, 2, workflowId, requesterId);
        when(actions.approve(projectId, actionId, admin, "admin-key", "admin-request"))
                .thenReturn(approved);
        when(agentService.resume(
                projectId, requesterId, true, conversationId, workflowId, actionId,
                "APPROVE", "admin-key", "admin-request"))
                .thenReturn(new AgentResumeResult(
                        conversationId, workflowId, actionId, "APPROVE", "RESUMED", "admin-request"));
        when(actions.executeApproved(projectId, actionId, admin, "admin-key", "admin-request"))
                .thenReturn(executed);

        assertThat(workflow.confirm(projectId, actionId, admin, "admin-key", "admin-request").status())
                .isEqualTo(AgentActionStatus.EXECUTED);

        InOrder order = inOrder(actions, agentService);
        order.verify(actions).approve(projectId, actionId, admin, "admin-key", "admin-request");
        order.verify(agentService).resume(
                projectId, requesterId, true, conversationId, workflowId, actionId,
                "APPROVE", "admin-key", "admin-request");
        order.verify(actions).executeApproved(
                projectId, actionId, admin, "admin-key", "admin-request");
    }

    @Test
    void adminRejectionResumesTheRequestersCheckpointAndKeepsTheAdminAsDecisionActor() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor admin = new AuthenticatedActor(adminId, true);
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        AgentActionView rejected = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.REJECTED, null, null, "Admin reject", null,
                "TODO", "HIGH", null, now, now, 2, workflowId, requesterId);
        when(actions.reject(projectId, actionId, admin, "admin-reject-key", "admin-reject-request"))
                .thenReturn(rejected);
        when(agentService.resume(
                projectId, requesterId, true, conversationId, workflowId, actionId,
                "REJECT", "admin-reject-key", "admin-reject-request"))
                .thenReturn(new AgentResumeResult(
                        conversationId, workflowId, actionId, "REJECT", "RESUMED", "admin-reject-request"));

        assertThat(workflow.reject(
                projectId, actionId, admin, "admin-reject-key", "admin-reject-request").status())
                .isEqualTo(AgentActionStatus.REJECTED);

        InOrder order = inOrder(actions, agentService);
        order.verify(actions).reject(
                projectId, actionId, admin, "admin-reject-key", "admin-reject-request");
        order.verify(agentService).resume(
                projectId, requesterId, true, conversationId, workflowId, actionId,
                "REJECT", "admin-reject-key", "admin-reject-request");
    }

    @Test
    void workflowActionWithoutAPersistedCheckpointOwnerFailsClosed() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        when(actions.approve(projectId, actionId, actor, "missing-owner-key", "missing-owner-request"))
                .thenReturn(new AgentActionView(
                        actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                        AgentActionStatus.APPROVED, null, null, "Missing owner", null,
                        "TODO", "HIGH", null, now, now, 2, workflowId));

        assertThatThrownBy(() -> workflow.confirm(
                projectId, actionId, actor, "missing-owner-key", "missing-owner-request"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Agent action is missing its checkpoint owner.");

        verifyNoInteractions(agentService);
        verify(actions, never()).executeApproved(
                projectId, actionId, actor, "missing-owner-key", "missing-owner-request");
    }

    @Test
    void automaticConfirmationResumesTheInterruptedThreadAndExecutesOnce() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        AgentActionView approved = new AgentActionView(actionId, projectId, conversationId,
                AgentActionType.CREATE_TASK, AgentActionStatus.APPROVED, null, null,
                "Timed create", null, "TODO", "LOW", null, now.minusSeconds(61), null,
                2, workflowId, actor.userId());
        AgentActionView executed = new AgentActionView(actionId, projectId, conversationId,
                AgentActionType.CREATE_TASK, AgentActionStatus.EXECUTED, null, null,
                "Timed create", null, "TODO", "LOW", null, now.minusSeconds(61), now,
                2, workflowId, actor.userId());
        when(actions.approveAutomatically(projectId, actionId, actor, "auto-key", "auto-request"))
                .thenReturn(approved);
        when(agentService.resume(projectId, actor.userId(), false, conversationId, workflowId, actionId,
                "APPROVE", "auto-key", "auto-request"))
                .thenReturn(new AgentResumeResult(
                        conversationId, workflowId, actionId, "APPROVE", "RESUMED", "auto-request"));
        when(actions.executeApproved(projectId, actionId, actor, "auto-key", "auto-request"))
                .thenReturn(executed);

        assertThat(workflow.confirmAutomatically(projectId, actionId, actor, "auto-key", "auto-request").status())
                .isEqualTo(AgentActionStatus.EXECUTED);
        InOrder order = inOrder(actions, agentService);
        order.verify(actions).approveAutomatically(projectId, actionId, actor, "auto-key", "auto-request");
        order.verify(agentService).resume(
                projectId, actor.userId(), false, conversationId, workflowId, actionId,
                "APPROVE", "auto-key", "auto-request");
        order.verify(actions).executeApproved(projectId, actionId, actor, "auto-key", "auto-request");
    }

    @Test
    void confirmResumesTheInterruptedThreadBeforeExecutingTheApprovedAction() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        AgentActionView approved = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.APPROVED, null, null, "Resume", null,
                "TODO", "HIGH", null, now, null, 2, workflowId, userId);
        AgentActionView executed = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.EXECUTED, null, null, "Resume", null,
                "TODO", "HIGH", null, now, now, 2, workflowId, userId);
        when(actions.approve(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(approved);
        when(agentService.resume(
                projectId, userId, false, conversationId, workflowId, actionId,
                "APPROVE", "workflow-key", "request-1"))
                .thenReturn(new AgentResumeResult(
                        conversationId, workflowId, actionId, "APPROVE", "RESUMED", "request-1"));
        when(actions.executeApproved(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(executed);

        AgentActionView result = workflow.confirm(
                projectId, actionId, actor, "workflow-key", "request-1");

        assertThat(result.status()).isEqualTo(AgentActionStatus.EXECUTED);
        InOrder order = inOrder(actions, agentService);
        order.verify(actions).approve(projectId, actionId, actor, "workflow-key", "request-1");
        order.verify(agentService).resume(
                projectId, userId, false, conversationId, workflowId, actionId,
                "APPROVE", "workflow-key", "request-1");
        order.verify(actions).executeApproved(
                projectId, actionId, actor, "workflow-key", "request-1");
    }

    @Test
    void confirmDoesNotExecuteWhenResumeResponseBelongsToAnotherAction() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        when(actions.approve(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(new AgentActionView(
                        actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                        AgentActionStatus.APPROVED, null, null, "Resume", null,
                        "TODO", "HIGH", null, now, null, 2, workflowId, userId));
        when(agentService.resume(
                projectId, userId, false, conversationId, workflowId, actionId,
                "APPROVE", "workflow-key", "request-1"))
                .thenReturn(new AgentResumeResult(
                        conversationId, workflowId, UUID.randomUUID(),
                        "APPROVE", "RESUMED", "request-1"));

        assertThatThrownBy(() -> workflow.confirm(
                projectId, actionId, actor, "workflow-key", "request-1"))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(actions, never()).executeApproved(
                projectId, actionId, actor, "workflow-key", "request-1");
    }

    @Test
    void confirmDoesNotExecuteWhenResumeResponseBelongsToAnotherWorkflowRound() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        when(actions.approve(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(new AgentActionView(
                        actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                        AgentActionStatus.APPROVED, null, null, "Resume", null,
                        "TODO", "HIGH", null, now, null, 2, workflowId, userId));
        when(agentService.resume(
                projectId, userId, false, conversationId, workflowId, actionId,
                "APPROVE", "workflow-key", "request-1"))
                .thenReturn(new AgentResumeResult(
                        conversationId, UUID.randomUUID(), actionId,
                        "APPROVE", "RESUMED", "request-1"));

        assertThatThrownBy(() -> workflow.confirm(
                projectId, actionId, actor, "workflow-key", "request-1"))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(actions, never()).executeApproved(
                projectId, actionId, actor, "workflow-key", "request-1");
    }

    @Test
    void confirmExecutesLegacyPendingActionWithoutRequiringAMissingCheckpoint() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        AgentActionView approvedLegacyAction = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.APPROVED, null, null, "Legacy", null,
                "TODO", "HIGH", null, now, null, null);
        AgentActionView executed = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.EXECUTED, null, null, "Legacy", null,
                "TODO", "HIGH", null, now, now, null);
        when(actions.approve(projectId, actionId, actor, "legacy-key", "request-legacy"))
                .thenReturn(approvedLegacyAction);
        when(actions.executeApproved(projectId, actionId, actor, "legacy-key", "request-legacy"))
                .thenReturn(executed);

        AgentActionView result = workflow.confirm(
                projectId, actionId, actor, "legacy-key", "request-legacy");

        assertThat(result.status()).isEqualTo(AgentActionStatus.EXECUTED);
        verifyNoInteractions(agentService);
    }

    @Test
    void rejectResumesTheInterruptedThreadAfterPersistingTheDecision() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        AgentActionView rejected = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.REJECTED, null, null, "Reject", null,
                "TODO", "HIGH", null, now, now, 2, workflowId, userId);
        when(actions.reject(projectId, actionId, actor, "reject-key", "request-reject"))
                .thenReturn(rejected);
        when(agentService.resume(
                projectId, userId, false, conversationId, workflowId, actionId,
                "REJECT", "reject-key", "request-reject"))
                .thenReturn(new AgentResumeResult(
                        conversationId, workflowId, actionId, "REJECT", "RESUMED", "request-reject"));

        AgentActionView result = workflow.reject(
                projectId, actionId, actor, "reject-key", "request-reject");

        assertThat(result.status()).isEqualTo(AgentActionStatus.REJECTED);
        InOrder order = inOrder(actions, agentService);
        order.verify(actions).reject(
                projectId, actionId, actor, "reject-key", "request-reject");
        order.verify(agentService).resume(
                projectId, userId, false, conversationId, workflowId, actionId,
                "REJECT", "reject-key", "request-reject");
    }
}
