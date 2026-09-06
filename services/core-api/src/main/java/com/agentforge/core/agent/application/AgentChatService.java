package com.agentforge.core.agent.application;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

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

    public AgentChatCommand prepareStream(
            UUID projectId,
            AuthenticatedActor actor,
            String message,
            UUID conversationId,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        aiUsageQuota.consume(actor.userId());
        return new AgentChatCommand(projectId, actor, message.trim(), conversationId, requestId);
    }

    public void stream(AgentChatCommand command, Consumer<AgentStreamEvent> sink) {
        AtomicReference<UUID> effectiveConversationId = new AtomicReference<>(command.conversationId());
        agentServiceClient.stream(
                command.projectId(),
                command.actor().userId(),
                command.actor().admin(),
                command.message(),
                command.conversationId(),
                command.requestId(),
                event -> {
                    if ("metadata".equals(event.type())) {
                        effectiveConversationId.set(event.conversationId());
                    }
                    sink.accept(finalizeEvent(command, effectiveConversationId.get(), event));
                });
    }

    private AgentStreamEvent finalizeEvent(
            AgentChatCommand command, UUID conversationId, AgentStreamEvent event) {
        if (!"complete".equals(event.type())) {
            return event;
        }
        AgentActionView pendingAction = null;
        if (event.toolProposal() != null) {
            pendingAction = agentActionService.createPending(
                    command.projectId(),
                    command.actor(),
                    conversationId,
                    event.toolProposal())
                    .orElse(null);
        }
        return AgentStreamEvent.completed(pendingAction);
    }
}
