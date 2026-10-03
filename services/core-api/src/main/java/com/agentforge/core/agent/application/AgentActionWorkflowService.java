package com.agentforge.core.agent.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.dao.OptimisticLockingFailureException;

import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ServiceUnavailableException;

@Service
public class AgentActionWorkflowService {

    private final AgentActionService actions;
    private final AgentServiceClient agentService;

    public AgentActionWorkflowService(
            AgentActionService actions,
            AgentServiceClient agentService) {
        this.actions = actions;
        this.agentService = agentService;
    }

    public AgentActionView confirm(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        return confirmInternal(projectId, actionId, actor, idempotencyKey, requestId, false);
    }

    public AgentActionView confirmAutomatically(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        return confirmInternal(projectId, actionId, actor, idempotencyKey, requestId, true);
    }

    private AgentActionView confirmInternal(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId,
            boolean automatic) {
        AgentActionView approved = automatic
                ? actions.approveAutomatically(projectId, actionId, actor, idempotencyKey, requestId)
                : actions.approve(projectId, actionId, actor, idempotencyKey, requestId);
        if (approved.status() != AgentActionStatus.APPROVED) {
            return approved;
        }
        if (approved.actionWorkflowVersion() != null) {
            AgentResumeResult resumed = agentService.resume(
                    projectId,
                    requireCheckpointOwner(approved),
                    actor.admin(),
                    approved.conversationId(),
                    approved.actionWorkflowId(),
                    actionId,
                    "APPROVE",
                    idempotencyKey,
                    requestId);
            requireMatchingResume(
                    resumed, approved.conversationId(), approved.actionWorkflowId(), actionId, "APPROVE");
        }
        try {
            return actions.executeApproved(
                    projectId, actionId, actor, idempotencyKey, requestId);
        }
        catch (OptimisticLockingFailureException exception) {
            if (approved.actionType() != com.agentforge.core.agent.domain.AgentActionType.UPDATE_TASK) {
                throw exception;
            }
            return actions.failApprovedAfterOptimisticConflict(
                    projectId, actionId, actor, idempotencyKey, requestId);
        }
    }

    public AgentActionView reject(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        AgentActionView rejected = actions.reject(
                projectId, actionId, actor, idempotencyKey, requestId);
        if (rejected.actionWorkflowVersion() != null) {
            AgentResumeResult resumed = agentService.resume(
                    projectId,
                    requireCheckpointOwner(rejected),
                    actor.admin(),
                    rejected.conversationId(),
                    rejected.actionWorkflowId(),
                    actionId,
                    "REJECT",
                    idempotencyKey,
                    requestId);
            requireMatchingResume(
                    resumed, rejected.conversationId(), rejected.actionWorkflowId(), actionId, "REJECT");
        }
        return rejected;
    }

    private UUID requireCheckpointOwner(AgentActionView action) {
        if (action.requestedByUserId() == null) {
            throw new ServiceUnavailableException("Agent action is missing its checkpoint owner.");
        }
        return action.requestedByUserId();
    }

    private void requireMatchingResume(
            AgentResumeResult resumed,
            UUID conversationId,
            UUID actionWorkflowId,
            UUID actionId,
            String decision) {
        if (resumed == null
                || !conversationId.equals(resumed.conversationId())
                || !java.util.Objects.equals(actionWorkflowId, resumed.actionWorkflowId())
                || !actionId.equals(resumed.actionId())
                || !decision.equals(resumed.decision())
                || !"RESUMED".equals(resumed.status())) {
            throw new ServiceUnavailableException("Agent Service returned an invalid resume response.");
        }
    }
}
