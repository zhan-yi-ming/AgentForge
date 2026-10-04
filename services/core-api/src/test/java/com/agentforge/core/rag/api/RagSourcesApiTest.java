package com.agentforge.core.rag.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.agentforge.core.rag.application.RagSourceSnapshot;
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
        when(ragSourceService.snapshot(eq(projectId), eq(userId), eq(false), isNull())).thenReturn(
                new RagSourceSnapshot(42, true, List.of(
                    new RagSource("WIKI", sourceId, 2, "Architecture", "# Core"))));

        mockMvc.perform(post("/internal/v1/rag/sources")
                        .header("X-AgentForge-Core-Internal-Token", "test-only-core-token")
                        .header("X-Request-Id", "request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.snapshotVersion").value(42))
                .andExpect(jsonPath("$.sourcesChanged").value(true))
                .andExpect(jsonPath("$.requestId").value("request-123"))
                .andExpect(jsonPath("$.sources[0].sourceType").value("WIKI"))
                .andExpect(jsonPath("$.sources[0].sourceId").value(sourceId.toString()));
    }

    @Test
    void matchingKnownSnapshotReturnsNoSourceBodies() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(ragSourceService.snapshot(projectId, userId, false, 42L)).thenReturn(
                new RagSourceSnapshot(42, false, List.of()));

        mockMvc.perform(post("/internal/v1/rag/sources")
                        .header("X-AgentForge-Core-Internal-Token", "test-only-core-token")
                        .header("X-Request-Id", "request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, userId, 42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").value(42))
                .andExpect(jsonPath("$.sourcesChanged").value(false))
                .andExpect(jsonPath("$.sources").isEmpty());
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
        return body(projectId, userId, null);
    }

    private String body(UUID projectId, UUID userId, Long knownSnapshotVersion) {
        String known = knownSnapshotVersion == null
                ? ""
                : "," + "\"knownSnapshotVersion\":" + knownSnapshotVersion;
        return """
                {"projectId":"%s","userId":"%s","actorAdmin":false%s,"requestId":"request-123"}
                """.formatted(projectId, userId, known);
    }
}
