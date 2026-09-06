package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

class AgentChatServiceTest {

    @Test
    void streamAuthorizesAndConsumesQuotaBeforeForwardingOrderedEvents() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<AgentStreamEvent> sink = invocation.getArgument(6);
            sink.accept(AgentStreamEvent.metadata(conversationId, "request-stream", List.of()));
            sink.accept(AgentStreamEvent.delta("第一段"));
            sink.accept(AgentStreamEvent.delta("，第二段"));
            sink.accept(AgentStreamEvent.complete(null));
            return null;
        }).when(client).stream(eq(projectId), eq(userId), eq(false), eq("hello"), eq(null),
                eq("request-stream"), any());

        AgentChatCommand command = service.prepareStream(
                projectId, actor, "  hello  ", null, "request-stream");
        List<AgentStreamEvent> events = new ArrayList<>();
        service.stream(command, events::add);

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly("metadata", "delta", "delta", "complete");
        assertThat(events).extracting(AgentStreamEvent::text)
                .containsExactly(null, "第一段", "，第二段", null);
        InOrder order = inOrder(projectAccess, quota, client);
        order.verify(projectAccess).requireAccess(projectId, actor);
        order.verify(quota).consume(userId);
        order.verify(client).stream(eq(projectId), eq(userId), eq(false), eq("hello"), eq(null),
                eq("request-stream"), any());
    }

    @Test
    void streamPersistsProposalUsingConversationFromMetadataBeforeCompleting() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Interview task", null, "TODO", "HIGH");
        AgentActionView pending = org.mockito.Mockito.mock(AgentActionView.class);
        when(actionService.createPending(projectId, actor, conversationId, proposal))
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
        verify(actionService).createPending(projectId, actor, conversationId, proposal);
    }

    @Test
    void chatAuthorizesBeforeCallingPythonAndTrimsMessage() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota);
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
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal("CREATE_TASK", null, null, "Add login", null, "TODO", "HIGH");
        AgentActionView pending = org.mockito.Mockito.mock(AgentActionView.class);
        when(client.chat(projectId, userId, false, "create", null, "request-2"))
                .thenReturn(new AgentChatResult(conversationId, "Please confirm", "request-2", java.util.List.of(), proposal, null));
        when(actionService.createPending(projectId, actor, conversationId, proposal)).thenReturn(Optional.of(pending));

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
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        ToolProposal proposal = new ToolProposal("CREATE_TASK", null, null, "x".repeat(201), null, "TODO", "HIGH");
        when(client.chat(projectId, userId, false, "create", null, "request-3"))
                .thenReturn(new AgentChatResult(conversationId, "Ordinary answer", "request-3", java.util.List.of(), proposal, null));
        when(actionService.createPending(projectId, actor, conversationId, proposal)).thenReturn(Optional.empty());

        AgentChatResult result = service.chat(projectId, actor, "create", null, "request-3");

        assertThat(result.answer()).isEqualTo("Ordinary answer");
        assertThat(result.toolProposal()).isNull();
        assertThat(result.pendingAction()).isNull();
    }

    @Test
    void exhaustedQuotaStopsBeforeCallingPython() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AiUsageQuota quota = org.mockito.Mockito.mock(AiUsageQuota.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService, quota);
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
