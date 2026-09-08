package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void confirmResumesTheInterruptedThreadBeforeExecutingTheApprovedAction() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        AgentActionView approved = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.APPROVED, null, null, "Resume", null,
                "TODO", "HIGH", null, now, null, 1);
        AgentActionView executed = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.EXECUTED, null, null, "Resume", null,
                "TODO", "HIGH", null, now, now, 1);
        when(actions.approve(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(approved);
        when(agentService.resume(
                projectId, userId, false, conversationId, actionId,
                "APPROVE", "workflow-key", "request-1"))
                .thenReturn(new AgentResumeResult(
                        conversationId, actionId, "APPROVE", "RESUMED", "request-1"));
        when(actions.executeApproved(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(executed);

        AgentActionView result = workflow.confirm(
                projectId, actionId, actor, "workflow-key", "request-1");

        assertThat(result.status()).isEqualTo(AgentActionStatus.EXECUTED);
        InOrder order = inOrder(actions, agentService);
        order.verify(actions).approve(projectId, actionId, actor, "workflow-key", "request-1");
        order.verify(agentService).resume(
                projectId, userId, false, conversationId, actionId,
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
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        when(actions.approve(projectId, actionId, actor, "workflow-key", "request-1"))
                .thenReturn(new AgentActionView(
                        actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                        AgentActionStatus.APPROVED, null, null, "Resume", null,
                        "TODO", "HIGH", null, now, null, 1));
        when(agentService.resume(
                projectId, userId, false, conversationId, actionId,
                "APPROVE", "workflow-key", "request-1"))
                .thenReturn(new AgentResumeResult(
                        conversationId, UUID.randomUUID(), "APPROVE", "RESUMED", "request-1"));

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
        verify(agentService, never()).resume(
                projectId, userId, false, conversationId, actionId,
                "APPROVE", "legacy-key", "request-legacy");
    }

    @Test
    void rejectResumesTheInterruptedThreadAfterPersistingTheDecision() {
        AgentActionService actions = mock(AgentActionService.class);
        AgentServiceClient agentService = mock(AgentServiceClient.class);
        AgentActionWorkflowService workflow = new AgentActionWorkflowService(actions, agentService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        AgentActionView rejected = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.REJECTED, null, null, "Reject", null,
                "TODO", "HIGH", null, now, now, 1);
        when(actions.reject(projectId, actionId, actor, "reject-key", "request-reject"))
                .thenReturn(rejected);
        when(agentService.resume(
                projectId, userId, false, conversationId, actionId,
                "REJECT", "reject-key", "request-reject"))
                .thenReturn(new AgentResumeResult(
                        conversationId, actionId, "REJECT", "RESUMED", "request-reject"));

        AgentActionView result = workflow.reject(
                projectId, actionId, actor, "reject-key", "request-reject");

        assertThat(result.status()).isEqualTo(AgentActionStatus.REJECTED);
        InOrder order = inOrder(actions, agentService);
        order.verify(actions).reject(
                projectId, actionId, actor, "reject-key", "request-reject");
        order.verify(agentService).resume(
                projectId, userId, false, conversationId, actionId,
                "REJECT", "reject-key", "request-reject");
    }
}
