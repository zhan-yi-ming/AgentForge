package com.agentforge.core.agent.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.agentforge.core.conversation.application.ConversationHistoryService;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

@Service
public class AgentChatService {
    private final ProjectAccess projectAccess;
    private final AgentServiceClient agentServiceClient;
    private final AgentActionService agentActionService;
    private final AiUsageQuota aiUsageQuota;
    private final ConversationHistoryService conversationHistory;

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
        this.conversationHistory = Objects.requireNonNull(conversationHistory);
    }

    public AgentChatResult chat(
            UUID projectId,
            AuthenticatedActor actor,
            String message,
            UUID conversationId,
            String requestId) {
        return chat(projectId, actor, message, conversationId, requestId, AgentTaskType.ANSWER);
    }

    public AgentChatResult chat(UUID projectId, AuthenticatedActor actor, String message,
            UUID conversationId, String requestId, AgentTaskType taskType) {
        projectAccess.requireAccess(projectId, actor);
        if (conversationId != null) {
            conversationHistory.requireWritable(projectId, conversationId, actor);
        }
        aiUsageQuota.consume(actor.userId());
        UUID effectiveConversationId = effectiveConversationId(projectId, actor.userId(), conversationId, requestId);
        AgentChatResult result = taskType == AgentTaskType.ANSWER ? agentServiceClient.chat(
                projectId,
                actor.userId(),
                actor.admin(),
                message.trim(),
                effectiveConversationId,
                requestId) : agentServiceClient.chat(projectId, actor.userId(), actor.admin(),
                        message.trim(), effectiveConversationId, requestId, taskType);
        AgentChatResult finalized = finalizeResult(projectId, actor, requestId, taskType, result);
        persist(command(projectId, actor, message, effectiveConversationId, requestId), finalized);
        return finalized;
    }

    public AgentChatCommand prepareStream(
            UUID projectId,
            AuthenticatedActor actor,
            String message,
            UUID conversationId,
            String requestId) {
        return prepareStream(projectId, actor, message, conversationId, requestId, AgentTaskType.ANSWER);
    }

    public AgentChatCommand prepareStream(UUID projectId, AuthenticatedActor actor, String message,
            UUID conversationId, String requestId, AgentTaskType taskType) {
        projectAccess.requireAccess(projectId, actor);
        if (conversationId != null) {
            conversationHistory.requireWritable(projectId, conversationId, actor);
        }
        aiUsageQuota.consume(actor.userId());
        UUID effectiveConversationId = effectiveConversationId(projectId, actor.userId(), conversationId, requestId);
        return new AgentChatCommand(projectId, actor, message.trim(), effectiveConversationId, requestId, taskType);
    }

    public void stream(AgentChatCommand command, Consumer<AgentStreamEvent> sink) {
        AtomicReference<UUID> effectiveConversationId = new AtomicReference<>(command.conversationId());
        AtomicReference<List<AgentSource>> sources = new AtomicReference<>(List.of());
        StringBuilder answer = new StringBuilder();
        streamToAgent(
                command.projectId(),
                command.actor().userId(),
                command.actor().admin(),
                command.message(),
                command.conversationId(),
                command.requestId(),
                command.taskType(),
                event -> {
                    if ("metadata".equals(event.type())) {
                        effectiveConversationId.set(event.conversationId());
                        sources.set(event.sources());
                    }
                    else if ("delta".equals(event.type()) && event.text() != null) {
                        answer.append(event.text());
                    }
                    else if ("complete".equals(event.type())) {
                        sources.set(event.sources());
                    }
                    AgentStreamEvent finalized = finalizeEvent(command, effectiveConversationId.get(), event);
                    if ("complete".equals(finalized.type())) {
                        persist(command, new AgentChatResult(effectiveConversationId.get(), answer.toString(),
                                command.requestId(), sources.get()));
                    }
                    sink.accept(finalized);
                });
    }

    private void streamToAgent(UUID projectId, UUID userId, boolean actorAdmin, String message,
            UUID conversationId, String requestId, AgentTaskType taskType, Consumer<AgentStreamEvent> sink) {
        if (taskType == AgentTaskType.ANSWER) {
            agentServiceClient.stream(projectId, userId, actorAdmin, message, conversationId, requestId, sink);
        } else {
            agentServiceClient.stream(projectId, userId, actorAdmin, message, conversationId, requestId, taskType, sink);
        }
    }

    private AgentChatCommand command(UUID projectId, AuthenticatedActor actor, String message,
            UUID conversationId, String requestId) {
        return new AgentChatCommand(projectId, actor, message.trim(), conversationId, requestId);
    }

    private UUID effectiveConversationId(
            UUID projectId, UUID userId, UUID conversationId, String requestId) {
        if (conversationId != null) {
            return conversationId;
        }
        String seed = "agentforge-chat-v1:" + projectId + ":" + userId + ":" + requestId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private void persist(AgentChatCommand command, AgentChatResult result) {
        conversationHistory.appendCompletedExchange(command.projectId(), command.actor(),
                result.conversationId(), command.message(), result.answer(), result.sources(), command.requestId());
    }

    private AgentStreamEvent finalizeEvent(
            AgentChatCommand command, UUID conversationId, AgentStreamEvent event) {
        if (!"complete".equals(event.type())) {
            return event;
        }
        AgentActionView pendingAction = null;
        if (event.toolProposal() != null) {
            if (command.taskType().allowsToolProposal()) {
                pendingAction = createPendingOrAbort(
                        command.projectId(), command.actor(), conversationId,
                        event.toolProposal(), command.requestId())
                        .orElse(null);
            }
            else {
                abortWaitingRound(command.projectId(), command.actor(), conversationId,
                        event.toolProposal(), command.requestId());
            }
        }
        return AgentStreamEvent.completed(pendingAction, event.sources());
    }

    private AgentChatResult finalizeResult(UUID projectId, AuthenticatedActor actor, String requestId,
            AgentTaskType taskType, AgentChatResult result) {
        if (result.toolProposal() == null) {
            return result;
        }
        if (!taskType.allowsToolProposal()) {
            abortWaitingRound(projectId, actor, result.conversationId(), result.toolProposal(), requestId);
            return result.withoutToolProposal();
        }
        return createPendingOrAbort(projectId, actor, result.conversationId(), result.toolProposal(), requestId)
                .map(result::withPendingAction)
                .orElseGet(result::withoutToolProposal);
    }

    private Optional<AgentActionView> createPendingOrAbort(
            UUID projectId,
            AuthenticatedActor actor,
            UUID conversationId,
            ToolProposal proposal,
            String requestId) {
        Optional<AgentActionView> pending;
        try {
            pending = agentActionService.createPending(
                    projectId, actor, conversationId, proposal, requestId);
        }
        catch (RuntimeException failure) {
            try {
                abortWaitingRound(projectId, actor, conversationId, proposal, requestId);
            }
            catch (RuntimeException compensationFailure) {
                compensationFailure.addSuppressed(failure);
                throw compensationFailure;
            }
            throw failure;
        }
        if (pending.isEmpty()) {
            abortWaitingRound(projectId, actor, conversationId, proposal, requestId);
        }
        return pending;
    }

    private void abortWaitingRound(
            UUID projectId,
            AuthenticatedActor actor,
            UUID conversationId,
            ToolProposal proposal,
            String requestId) {
        AgentAbortResult aborted = agentServiceClient.abort(
                projectId,
                actor.userId(),
                actor.admin(),
                conversationId,
                proposal.actionWorkflowId(),
                requestId);
        if (aborted == null
                || !conversationId.equals(aborted.conversationId())
                || (proposal.actionWorkflowId() != null
                    && !proposal.actionWorkflowId().equals(aborted.actionWorkflowId()))
                || !"ABORTED".equals(aborted.status())
                || !requestId.equals(aborted.requestId())) {
            throw new com.agentforge.core.shared.error.ServiceUnavailableException(
                    "Agent Service returned an invalid abort response.");
        }
    }
}
