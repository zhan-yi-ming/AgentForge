package com.agentforge.core.agent.application;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentforge.core.conversation.application.ConversationHistoryService;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

@Service
public class AgentChatService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentChatService.class);

    private final ProjectAccess projectAccess;
    private final AgentServiceClient agentServiceClient;
    private final AgentActionService agentActionService;
    private final AiUsageQuota aiUsageQuota;
    private final ConversationHistoryService conversationHistory;

    public AgentChatService(
            ProjectAccess projectAccess,
            AgentServiceClient agentServiceClient,
            AgentActionService agentActionService,
            AiUsageQuota aiUsageQuota) {
        this(projectAccess, agentServiceClient, agentActionService, aiUsageQuota, null);
    }

    @Autowired
    public AgentChatService(
            ProjectAccess projectAccess,
            AgentServiceClient agentServiceClient,
            AgentActionService agentActionService,
            AiUsageQuota aiUsageQuota,
            ConversationHistoryService conversationHistory) {
        this.projectAccess = projectAccess;
        this.agentServiceClient = agentServiceClient;
        this.agentActionService = agentActionService;
        this.aiUsageQuota = aiUsageQuota;
        this.conversationHistory = conversationHistory;
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
        AgentChatResult finalized = result.toolProposal() == null ? result : agentActionService.createPending(
                projectId,
                actor,
                result.conversationId(),
                result.toolProposal(),
                requestId)
                .map(result::withPendingAction)
                .orElseGet(result::withoutToolProposal);
        persist(command(projectId, actor, message, conversationId, requestId), finalized);
        return finalized;
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
        AtomicReference<List<AgentSource>> sources = new AtomicReference<>(List.of());
        StringBuilder answer = new StringBuilder();
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
                        sources.set(event.sources());
                    }
                    else if ("delta".equals(event.type()) && event.text() != null) {
                        answer.append(event.text());
                    }
                    AgentStreamEvent finalized = finalizeEvent(command, effectiveConversationId.get(), event);
                    sink.accept(finalized);
                    if ("complete".equals(finalized.type())) {
                        try {
                            persist(command, new AgentChatResult(effectiveConversationId.get(), answer.toString(),
                                    command.requestId(), sources.get()));
                        }
                        catch (RuntimeException exception) {
                            LOGGER.warn("Unable to persist completed Agent exchange; requestId={}, cause={}",
                                    command.requestId(), exception.getClass().getSimpleName());
                        }
                    }
                });
    }

    private AgentChatCommand command(UUID projectId, AuthenticatedActor actor, String message,
            UUID conversationId, String requestId) {
        return new AgentChatCommand(projectId, actor, message.trim(), conversationId, requestId);
    }

    private void persist(AgentChatCommand command, AgentChatResult result) {
        if (conversationHistory != null) {
            conversationHistory.appendCompletedExchange(command.projectId(), command.actor(),
                    result.conversationId(), command.message(), result.answer(), result.sources());
        }
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
                    event.toolProposal(),
                    command.requestId())
                    .orElse(null);
        }
        return AgentStreamEvent.completed(pendingAction);
    }
}
