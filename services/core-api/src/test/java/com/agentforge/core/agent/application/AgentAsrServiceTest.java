package com.agentforge.core.agent.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ServiceUnavailableException;

class AgentAsrServiceTest {
    @Test
    void authorizedStartSendsScopedRequestAndConsumesOneQuota() {
        ProjectAccess access = mock(ProjectAccess.class);
        AiUsageQuota quota = mock(AiUsageQuota.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://contract.test")
                .defaultHeader("X-AgentForge-Internal-Token", "test-only-internal-token");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID projectId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        UUID sessionId = UUID.randomUUID();
        server.expect(request -> {
            assertEquals("/internal/v1/asr/sessions", request.getURI().getPath());
            assertEquals("test-only-internal-token", request.getHeaders().getFirst("X-AgentForge-Internal-Token"));
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertTrue(body.contains(projectId.toString()));
            assertTrue(body.contains(actor.userId().toString()));
        }).andRespond(withSuccess("{\"sessionId\":\"" + sessionId + "\"}", MediaType.APPLICATION_JSON));
        AgentAsrService service = new AgentAsrService(access, quota, builder.build());

        assertEquals(sessionId, service.start(projectId, actor));
        verify(access).requireAccess(projectId, actor);
        verify(quota).consume(actor.userId());
        server.verify();
    }

    @Test
    void unavailableProviderDoesNotConsumeChatQuota() {
        ProjectAccess access = mock(ProjectAccess.class);
        AiUsageQuota quota = mock(AiUsageQuota.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://contract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertEquals("/internal/v1/asr/sessions", request.getURI().getPath()))
                .andRespond(withServerError());
        AgentAsrService service = new AgentAsrService(access, quota, builder.build());

        assertThrows(ServiceUnavailableException.class,
                () -> service.start(UUID.randomUUID(), new AuthenticatedActor(UUID.randomUUID(), false)));
        verifyNoInteractions(quota);
        server.verify();
    }

    @Test
    void deniedProjectCannotOpenProviderSessionOrConsumeQuota() {
        ProjectAccess access = mock(ProjectAccess.class);
        AiUsageQuota quota = mock(AiUsageQuota.class);
        RestClient remote = mock(RestClient.class);
        UUID projectId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        doThrow(new ForbiddenException("Project access denied.")).when(access).requireAccess(projectId, actor);
        AgentAsrService service = new AgentAsrService(access, quota, remote);

        assertThrows(ForbiddenException.class, () -> service.start(projectId, actor));
        verifyNoInteractions(quota, remote);
    }
}
