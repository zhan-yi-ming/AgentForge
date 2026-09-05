package com.agentforge.core.rag.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.rag.application.CoreInternalAuthentication;
import com.agentforge.core.rag.application.RagSource;
import com.agentforge.core.rag.application.RagSourceService;
import com.agentforge.core.rag.infrastructure.CoreInternalConfiguration;
import com.agentforge.core.security.SecurityConfiguration;
import com.agentforge.core.security.SecurityProblemWriter;
import com.agentforge.core.shared.web.RequestIdFilter;

@WebMvcTest(RagSourcesController.class)
@Import({
    SecurityConfiguration.class,
    SecurityProblemWriter.class,
    RequestIdFilter.class,
    CoreInternalConfiguration.class,
    CoreInternalAuthentication.class
})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M",
    "agentforge.core-internal.token=test-only-core-token"
})
class RagSourcesApiTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RagSourceService ragSourceService;

    @Test
    void internalTokenReturnsAuthorizedSources() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(ragSourceService.list(eq(projectId), eq(userId), eq(false))).thenReturn(List.of(
                new RagSource("WIKI", sourceId, 2, "Architecture", "# Core")));

        mockMvc.perform(post("/internal/v1/rag/sources")
                        .header("X-AgentForge-Core-Internal-Token", "test-only-core-token")
                        .header("X-Request-Id", "request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.requestId").value("request-123"))
                .andExpect(jsonPath("$.sources[0].sourceType").value("WIKI"))
                .andExpect(jsonPath("$.sources[0].sourceId").value(sourceId.toString()));
    }

    @Test
    void missingOrBearerOnlyCredentialsCannotReadSources() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/internal/v1/rag/sources")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, userId)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/v1/rag/sources")
                        .header("X-AgentForge-Core-Internal-Token", "wrong-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, userId)))
                .andExpect(status().isUnauthorized());
    }

    private String body(UUID projectId, UUID userId) {
        return """
                {"projectId":"%s","userId":"%s","actorAdmin":false,"requestId":"request-123"}
                """.formatted(projectId, userId);
    }
}
