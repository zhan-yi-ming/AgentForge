package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.UUID;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.conversation.application.ConversationHistoryService;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ServiceUnavailableException;

class AgentChatServiceTest {

    @Test
    void conversationHistoryDependencyCannotBeOmitted() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new AgentChatService(projects, client, actions, quota, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deletedConversationIsRejectedBeforeForwardingToAgent() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        org.mockito.Mockito.doThrow(new ConflictException("The conversation history was deleted."))
                .when(history).requireWritable(projectId, conversationId, actor);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.chat(projectId, actor,
                "question", conversationId, "request-deleted"))
                .isInstanceOf(ConflictException.class);
        verify(client, never()).chat(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any());
        verify(quota, never()).consume(any());
    }

    @Test
    void deletedConversationIsRejectedBeforeOpeningStreamOrChargingQuota() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        org.mockito.Mockito.doThrow(new ConflictException("The conversation history was deleted."))
                .when(history).requireWritable(projectId, conversationId, actor);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareStream(projectId, actor,
                "question", conversationId, "request-deleted-stream"))
                .isInstanceOf(ConflictException.class);
        verify(quota, never()).consume(any());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void chatPersistsOnlyTheCompletedServerResult() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        when(client.chat(eq(projectId), eq(userId), eq(false), eq("question"), any(UUID.class), eq("request-history")))
                .thenReturn(new AgentChatResult(conversationId, "answer", "request-history", List.of()));

        service.chat(projectId, actor, "question", null, "request-history");

        verify(history).appendCompletedExchange(
                projectId, actor, conversationId, "question", "answer", List.of(), "request-history");
    }

    @Test
    void failedStreamDoesNotPersistPartialAnswer() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.delta("partial"));
            throw new IllegalStateException("stream failed");
        }).when(client).stream(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.stream(new AgentChatCommand(projectId, actor, "question", null, "request"), event -> { }))
                .isInstanceOf(IllegalStateException.class);

        verify(history, never()).appendCompletedExchange(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void historyWriteFailurePreventsTheCompletedStreamEvent() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(history).appendCompletedExchange(any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-history-failure", List.of()));
            sink.accept(AgentStreamEvent.delta("complete answer"));
            sink.accept(AgentStreamEvent.complete(null));
            return null;
        }).when(client).stream(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any());
        List<AgentStreamEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> service.stream(
                new AgentChatCommand(projectId, actor, "question", null, "request-history-failure"), events::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("metadata", "delta");
    }

    @Test
    void completedStreamIsPersistedBeforeACompleteSinkFailure() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-disconnect", List.of()));
            sink.accept(AgentStreamEvent.delta("complete answer"));
            sink.accept(AgentStreamEvent.complete(null));
            return null;
        }).when(client).stream(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.stream(
                new AgentChatCommand(projectId, actor, "question", null, "request-disconnect"), event -> {
                    if ("complete".equals(event.type())) {
                        throw new IllegalStateException("client disconnected");
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client disconnected");

        verify(history).appendCompletedExchange(projectId, actor, conversationId,
                "question", "complete answer", List.of(), "request-disconnect");
    }

    @Test
    void streamAuthorizesAndConsumesQuotaBeforeForwardingOrderedEvents() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AgentSource citedSource = new AgentSource("WIKI", UUID.randomUUID(), "Architecture", "Java owns writes.");
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-stream", List.of()));
            sink.accept(AgentStreamEvent.delta("第一段"));
            sink.accept(AgentStreamEvent.delta("，第二段"));
            sink.accept(new AgentStreamEvent("complete", null, null, List.of(citedSource),
                    null, null, null, null));
            return null;
        }).when(client).stream(eq(projectId), eq(userId), eq(false), eq("hello"), any(UUID.class),
                eq("request-stream"), any());

        AgentChatCommand command = service.prepareStream(
                projectId, actor, "  hello  ", null, "request-stream");
        List<AgentStreamEvent> events = new ArrayList<>();
        service.stream(command, events::add);

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("metadata", "delta", "delta", "complete");
        assertThat(events).extracting(AgentStreamEvent::text)
                .containsExactly(null, "第一段", "，第二段", null);
        assertThat(events.getLast().sources()).containsExactly(citedSource);
        InOrder order = inOrder(projectAccess, quota, client);
        order.verify(projectAccess).requireAccess(projectId, actor);
        order.verify(quota).consume(userId);
        order.verify(client).stream(eq(projectId), eq(userId), eq(false), eq("hello"), any(UUID.class),
                eq("request-stream"), any());
        verify(history).appendCompletedExchange(
                projectId, actor, conversationId, "hello", "第一段，第二段", List.of(citedSource),
                "request-stream");
    }

    @Test
    void streamPersistsProposalUsingConversationFromMetadataBeforeCompleting() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota, org.mockito.Mockito.mock(ConversationHistoryService.class));
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Interview task", null, "TODO", "HIGH", UUID.randomUUID());
        AgentActionView pending = org.mockito.Mockito.mock(AgentActionView.class);
        when(actionService.createPending(projectId, actor, conversationId, proposal, "request-proposal"))
                .thenReturn(Optional.of(pending));
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-proposal", List.of()));
            sink.accept(AgentStreamEvent.complete(proposal));
            return null;
        }).when(client).stream(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any(), any());

        AgentChatCommand command = service.prepareStream(
                projectId, actor, "create", null, "request-proposal");
        List<AgentStreamEvent> events = new ArrayList<>();
        service.stream(command, events::add);

        assertThat(events.getLast().pendingAction()).isSameAs(pending);
        assertThat(events.getLast().toolProposal()).isNull();
        verify(actionService).createPending(projectId, actor, conversationId, proposal, "request-proposal");
    }

    @Test
    void streamAbortsTheExactWaitingRoundWhenPendingActionCannotBeCreated() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Invalid", " ", "TODO", "HIGH", workflowId);
        when(actions.createPending(projectId, actor, conversationId, proposal, "request-stream-abort"))
                .thenReturn(Optional.empty());
        when(client.abort(projectId, userId, false, conversationId, workflowId, "request-stream-abort"))
                .thenReturn(new AgentAbortResult(
                        conversationId, workflowId, "ABORTED", "request-stream-abort"));
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-stream-abort", List.of()));
            sink.accept(AgentStreamEvent.complete(proposal));
            return null;
        }).when(client).stream(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any(), any());

        AgentChatCommand command = service.prepareStream(
                projectId, actor, "create", conversationId, "request-stream-abort");
        List<AgentStreamEvent> events = new ArrayList<>();
        service.stream(command, events::add);

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("metadata", "complete");
        assertThat(events.getLast().pendingAction()).isNull();
        verify(client).abort(projectId, userId, false, conversationId, workflowId, "request-stream-abort");
    }

    @Test
    void streamPropagatesAbortFailureWithoutEmittingComplete() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Invalid", " ", "TODO", "HIGH", workflowId);
        when(actions.createPending(projectId, actor, conversationId, proposal, "request-stream-abort-fail"))
                .thenReturn(Optional.empty());
        when(client.abort(projectId, userId, false, conversationId, workflowId, "request-stream-abort-fail"))
                .thenThrow(new ServiceUnavailableException("abort unavailable"));
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-stream-abort-fail", List.of()));
            sink.accept(AgentStreamEvent.complete(proposal));
            return null;
        }).when(client).stream(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any(), any());
        AgentChatCommand command = service.prepareStream(
                projectId, actor, "create", conversationId, "request-stream-abort-fail");
        List<AgentStreamEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> service.stream(command, events::add))
                .isInstanceOf(ServiceUnavailableException.class);

        assertThat(events).extracting(AgentStreamEvent::type).containsExactly("metadata");
    }

    @Test
    void chatAuthorizesBeforeCallingPythonAndTrimsMessage() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota, org.mockito.Mockito.mock(ConversationHistoryService.class));
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        AgentChatResult expected = new AgentChatResult(conversationId, "answer", "request-1");
        when(client.chat(projectId, userId, false, "hello", conversationId, "request-1")).thenReturn(expected);

        AgentChatResult result = service.chat(projectId, actor, "  hello  ", conversationId, "request-1");

        assertThat(result).isEqualTo(expected);
        InOrder order = inOrder(projectAccess, quota, client);
        order.verify(projectAccess).requireAccess(projectId, actor);
        order.verify(quota).consume(userId);
        order.verify(client).chat(projectId, userId, false, "hello", conversationId, "request-1");
    }

    @Test
    void chatPersistsProposalAndReturnsPendingAction() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota, org.mockito.Mockito.mock(ConversationHistoryService.class));
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Add login", null, "TODO", "HIGH", UUID.randomUUID());
        AgentActionView pending = org.mockito.Mockito.mock(AgentActionView.class);
        when(client.chat(eq(projectId), eq(userId), eq(false), eq("create"), any(UUID.class), eq("request-2")))
                .thenReturn(new AgentChatResult(conversationId, "Please confirm", "request-2", java.util.List.of(), proposal, null));
        when(actionService.createPending(projectId, actor, conversationId, proposal, "request-2"))
                .thenReturn(Optional.of(pending));

        AgentChatResult result = service.chat(projectId, actor, "create", null, "request-2");

        assertThat(result.pendingAction()).isSameAs(pending);
        assertThat(result.toolProposal()).isNull();
    }

    @Test
    void chatIgnoresInvalidProposalAndKeepsOrdinaryAnswer() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota, org.mockito.Mockito.mock(ConversationHistoryService.class));
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "x".repeat(201), null, "TODO", "HIGH", null);
        UUID actualWorkflowId = UUID.randomUUID();
        when(client.chat(eq(projectId), eq(userId), eq(false), eq("create"), any(UUID.class), eq("request-3")))
                .thenReturn(new AgentChatResult(conversationId, "Ordinary answer", "request-3", java.util.List.of(), proposal, null));
        when(actionService.createPending(projectId, actor, conversationId, proposal, "request-3"))
                .thenReturn(Optional.empty());
        when(client.abort(projectId, userId, false, conversationId, null, "request-3"))
                .thenReturn(new AgentAbortResult(
                        conversationId, actualWorkflowId, "ABORTED", "request-3"));

        AgentChatResult result = service.chat(projectId, actor, "create", null, "request-3");

        assertThat(result.answer()).isEqualTo("Ordinary answer");
        assertThat(result.toolProposal()).isNull();
        assertThat(result.pendingAction()).isNull();
        verify(client).abort(projectId, userId, false, conversationId, null, "request-3");
    }

    @Test
    void chatAbortsTheExactWaitingRoundWhenActionCreationFails() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "UPDATE_TASK", UUID.randomUUID(), 3L, null, null, "DONE", null, workflowId);
        when(client.chat(projectId, userId, false, "update", conversationId, "request-stale"))
                .thenReturn(new AgentChatResult(
                        conversationId, "Please confirm", "request-stale", List.of(), proposal, null));
        when(actions.createPending(projectId, actor, conversationId, proposal, "request-stale"))
                .thenThrow(new ConflictException("The Task version is stale."));
        when(client.abort(projectId, userId, false, conversationId, workflowId, "request-stale"))
                .thenReturn(new AgentAbortResult(
                        conversationId, workflowId, "ABORTED", "request-stale"));

        assertThatThrownBy(() -> service.chat(
                projectId, actor, "update", conversationId, "request-stale"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("stale");

        verify(client).abort(
                projectId, userId, false, conversationId, workflowId, "request-stale");
        verify(history, never()).appendCompletedExchange(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void chatAbortsTheExactWaitingRoundWhenActionPersistenceFails() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Persist me", null, "TODO", "HIGH", workflowId);
        when(client.chat(projectId, userId, false, "create", conversationId, "request-db-failure"))
                .thenReturn(new AgentChatResult(
                        conversationId, "Please confirm", "request-db-failure", List.of(), proposal, null));
        when(actions.createPending(projectId, actor, conversationId, proposal, "request-db-failure"))
                .thenThrow(new IllegalStateException("database write failed"));
        when(client.abort(projectId, userId, false, conversationId, workflowId, "request-db-failure"))
                .thenReturn(new AgentAbortResult(
                        conversationId, workflowId, "ABORTED", "request-db-failure"));

        assertThatThrownBy(() -> service.chat(
                projectId, actor, "create", conversationId, "request-db-failure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database write failed");

        verify(client).abort(
                projectId, userId, false, conversationId, workflowId, "request-db-failure");
        verify(history, never()).appendCompletedExchange(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void chatDoesNotHideAnAbortFailureAsAnOrdinaryAnswer() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Invalid", null, "TODO", "HIGH", UUID.randomUUID());
        when(client.chat(projectId, userId, false, "create", conversationId, "request-abort-fails"))
                .thenReturn(new AgentChatResult(
                        conversationId, "Ordinary answer", "request-abort-fails", List.of(), proposal, null));
        when(actions.createPending(projectId, actor, conversationId, proposal, "request-abort-fails"))
                .thenReturn(Optional.empty());
        when(client.abort(projectId, userId, false, conversationId,
                proposal.actionWorkflowId(), "request-abort-fails"))
                .thenThrow(new ServiceUnavailableException("abort unavailable"));

        assertThatThrownBy(() -> service.chat(
                projectId, actor, "create", conversationId, "request-abort-fails"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void sameNewConversationRequestUsesOneDeterministicConversationId() {
        ProjectAccess projects = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actions = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        ConversationHistoryService history = org.mockito.Mockito.mock(ConversationHistoryService.class);
        AgentChatService service = new AgentChatService(projects, client, actions, quota, history);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        when(client.chat(eq(projectId), eq(userId), eq(false), eq("question"),
                org.mockito.ArgumentMatchers.nullable(UUID.class), eq("stable-request")))
                .thenAnswer(invocation -> new AgentChatResult(
                        invocation.getArgument(4), "answer", "stable-request", List.of()));

        service.chat(projectId, actor, "question", null, "stable-request");
        service.chat(projectId, actor, "question", null, "stable-request");

        ArgumentCaptor<UUID> conversations = ArgumentCaptor.forClass(UUID.class);
        verify(client, org.mockito.Mockito.times(2)).chat(
                eq(projectId), eq(userId), eq(false), eq("question"),
                conversations.capture(), eq("stable-request"));
        assertThat(conversations.getAllValues()).doesNotContainNull().allMatch(
                conversations.getAllValues().getFirst()::equals);
    }

    @Test
    void exhaustedQuotaStopsBeforeCallingPython() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota, org.mockito.Mockito.mock(ConversationHistoryService.class));
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        org.mockito.Mockito.doThrow(new com.agentforge.core.shared.error.RateLimitExceededException(
                "Daily AI request limit reached.")).when(quota).consume(userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.chat(projectId, actor, "hello", null, "request-4"))
                .isInstanceOf(com.agentforge.core.shared.error.RateLimitExceededException.class);

        verify(client, never()).chat(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any());
    }

}
