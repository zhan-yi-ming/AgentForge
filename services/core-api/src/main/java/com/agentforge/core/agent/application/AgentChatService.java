package com.agentforge.core.agent.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

@Service
public class AgentChatService {

    private final ProjectAccess projectAccess;
    private final AgentServiceClient agentServiceClient;
    private final AgentActionService agentActionService;
    private final AiUsageQuota aiUsageQuota;

    public AgentChatService(
            ProjectAccess projectAccess,
            AgentServiceClient agentServiceClient,
            AgentActionService agentActionService,
            AiUsageQuota aiUsageQuota) {
        this.projectAccess = projectAccess;
        this.agentServiceClient = agentServiceClient;
        this.agentActionService = agentActionService;
        this.aiUsageQuota = aiUsageQuota;
    }

    public AgentChatResult chat(
            UUID projectId,
            AuthenticatedActor actor,
            String message,
            UUID conversationId,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        aiUsageQuota.consume(actor.userId());
        AgentChatResult result = agentServiceClient.chat(
                projectId,
                actor.userId(),
                actor.admin(),
                message.trim(),
                conversationId,
                requestId);
        if (result.toolProposal() == null) {
            return result;
        }
        return agentActionService.createPending(
                projectId,
                actor,
                result.conversationId(),
                result.toolProposal())
                .map(result::withPendingAction)
                .orElseGet(result::withoutToolProposal);
    }
}
