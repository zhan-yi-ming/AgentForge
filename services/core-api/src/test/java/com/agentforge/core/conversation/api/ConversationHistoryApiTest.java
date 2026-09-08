package com.agentforge.core.conversation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.conversation.application.ConversationHistoryService;
import com.agentforge.core.conversation.application.ConversationSummaryView;
import com.agentforge.core.security.SecurityConfiguration;
import com.agentforge.core.security.SecurityProblemWriter;
import com.agentforge.core.shared.web.RequestIdFilter;

@WebMvcTest(ConversationHistoryController.class)
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWBiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", // gitleaks:allow public test-only key
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M"
})
class ConversationHistoryApiTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean ConversationHistoryService service;

    @Test
    void authenticatedUserListsProjectConversationSummaries() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-08T06:00:00Z");
        when(service.list(eq(projectId), any())).thenReturn(List.of(
                new ConversationSummaryView(conversationId, "Question", 2, now, now)));

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent/conversations", projectId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())
                                .claim("roles", List.of("USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$[0].preview").value("Question"));
    }

    @Test
    void anonymousHistoryRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent/conversations", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
