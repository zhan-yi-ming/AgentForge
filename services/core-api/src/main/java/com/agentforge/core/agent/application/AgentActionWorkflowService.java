package com.agentforge.core.agent.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

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
        AgentActionView approved = actions.approve(
                projectId, actionId, actor, idempotencyKey, requestId);
        if (approved.status() != AgentActionStatus.APPROVED) {
            return approved;
        }
        if (approved.actionWorkflowVersion() != null) {
            AgentResumeResult resumed = agentService.resume(
                    projectId,
                    actor.userId(),
                    actor.admin(),
                    approved.conversationId(),
                    actionId,
                    "APPROVE",
                    idempotencyKey,
                    requestId);
            requireMatchingResume(resumed, approved.conversationId(), actionId, "APPROVE");
        }
        return actions.executeApproved(
                projectId, actionId, actor, idempotencyKey, requestId);
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
                    actor.userId(),
                    actor.admin(),
                    rejected.conversationId(),
                    actionId,
                    "REJECT",
                    idempotencyKey,
                    requestId);
            requireMatchingResume(resumed, rejected.conversationId(), actionId, "REJECT");
        }
        return rejected;
    }

    private void requireMatchingResume(
            AgentResumeResult resumed,
            UUID conversationId,
            UUID actionId,
            String decision) {
        if (resumed == null
                || !conversationId.equals(resumed.conversationId())
                || !actionId.equals(resumed.actionId())
                || !decision.equals(resumed.decision())
                || !"RESUMED".equals(resumed.status())) {
            throw new ServiceUnavailableException("Agent Service returned an invalid resume response.");
        }
    }
}
