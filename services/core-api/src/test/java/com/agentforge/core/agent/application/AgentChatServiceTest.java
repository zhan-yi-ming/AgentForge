package com.agentforge.core.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

class AgentChatServiceTest {

    @Test
    void chatAuthorizesBeforeCallingPythonAndTrimsMessage() {
        ProjectAccess projectAccess = org.mockito.Mockito.mock(ProjectAccess.class);
        AgentServiceClient client = org.mockito.Mockito.mock(AgentServiceClient.class);
        AgentChatService service = new AgentChatService(projectAccess, client);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        AgentChatResult expected = new AgentChatResult(conversationId, "answer", "request-1");
        when(client.chat(projectId, userId, "hello", conversationId, "request-1")).thenReturn(expected);

        AgentChatResult result = service.chat(projectId, actor, "  hello  ", conversationId, "request-1");

        assertThat(result).isEqualTo(expected);
        InOrder order = inOrder(projectAccess, client);
        order.verify(projectAccess).requireAccess(projectId, actor);
        order.verify(client).chat(projectId, userId, "hello", conversationId, "request-1");
    }
}
