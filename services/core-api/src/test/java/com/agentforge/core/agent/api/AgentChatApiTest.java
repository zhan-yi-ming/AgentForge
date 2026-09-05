package com.agentforge.core.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.agent.application.AgentChatResult;
import com.agentforge.core.agent.application.AgentChatService;
import com.agentforge.core.agent.application.AgentSource;
import com.agentforge.core.agent.application.AgentActionView;
import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.security.SecurityConfiguration;
import com.agentforge.core.security.SecurityProblemWriter;
import com.agentforge.core.shared.error.ServiceUnavailableException;
import com.agentforge.core.shared.error.RateLimitExceededException;
import com.agentforge.core.shared.web.RequestIdFilter;

@WebMvcTest(AgentChatController.class)
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M"
})
class AgentChatApiTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AgentChatService agentChatService;

    @Test
    void chatReturnsAgentContractAndRequestId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(agentChatService.chat(eq(projectId), any(), eq("hello"), eq(conversationId), any()))
                .thenAnswer(invocation -> new AgentChatResult(
                        conversationId,
                        "Relevant project context",
                        invocation.getArgument(4),
                        List.of(new AgentSource("WIKI", UUID.randomUUID(), "Architecture", "Java owns writes."))));

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/chat", projectId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER"))))
                        .header("X-Request-Id", "request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello","conversationId":"%s"}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.answer").value("Relevant project context"))
                .andExpect(jsonPath("$.requestId").value("request-123"))
                .andExpect(jsonPath("$.sources[0].sourceType").value("WIKI"))
                .andExpect(jsonPath("$.sources[0].title").value("Architecture"));
    }

    @Test
    void chatMapsAgentFailureToServiceUnavailable() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(agentChatService.chat(eq(projectId), any(), eq("hello"), any(), any()))
                .thenThrow(new ServiceUnavailableException("Agent Service is unavailable."));

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/chat", projectId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"message\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://agentforge.local/problems/service-unavailable"));
    }

    @Test
    void chatRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/chat", UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatMapsDailyQuotaExhaustionToTooManyRequests() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(agentChatService.chat(eq(projectId), any(), eq("hello"), any(), any()))
                .thenThrow(new RateLimitExceededException("Daily AI request limit reached."));

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/chat", projectId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://agentforge.local/problems/rate-limit-exceeded"));
    }

    @Test
    void chatReturnsPendingActionPreviewWithoutInternalProposal() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        AgentActionView action = new AgentActionView(
                actionId, projectId, conversationId, AgentActionType.CREATE_TASK,
                AgentActionStatus.PENDING, null, null, "Add login", null,
                "TODO", "HIGH", null, now, null);
        when(agentChatService.chat(eq(projectId), any(), eq("create"), any(), any()))
                .thenReturn(new AgentChatResult(
                        conversationId, "Please confirm", "request-5", List.of(), null, action));

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/chat", projectId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"create\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAction.id").value(actionId.toString()))
                .andExpect(jsonPath("$.pendingAction.status").value("PENDING"))
                .andExpect(jsonPath("$.toolProposal").doesNotExist());
    }
}
