package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

class AgentChatServiceTest {

    @Test
    void chatAuthorizesBeforeCallingPythonAndTrimsMessage() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        AgentChatResult expected = new AgentChatResult(conversationId, "answer", "request-1");
        when(client.chat(projectId, userId, false, "hello", conversationId, "request-1")).thenReturn(expected);

        AgentChatResult result = service.chat(projectId, actor, "  hello  ", conversationId, "request-1");

        assertThat(result).isEqualTo(expected);
        InOrder order = inOrder(projectAccess, client);
        order.verify(projectAccess).requireAccess(projectId, actor);
        order.verify(client).chat(projectId, userId, false, "hello", conversationId, "request-1");
    }

    @Test
    void chatPersistsProposalAndReturnsPendingAction() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentActionService actionService = org.mockito.Mockito.mock(AgentActionService.class);
        AgentChatService service = new AgentChatService(projectAccess, client, actionService);
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
        AgentChatService service = new AgentChatService(projectAccess, client, actionService);
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

}
