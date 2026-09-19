package com.agentforge.core.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.agent.application.AgentAsrService;
import com.agentforge.core.security.SecurityConfiguration;
import com.agentforge.core.security.SecurityProblemWriter;
import com.agentforge.core.shared.web.RequestIdFilter;

@WebMvcTest(AgentAsrController.class)
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api"
})
class AgentAsrApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean AgentAsrService service;

    @Test
    void voiceAudioRequiresBearerAndForwardsPcmBytes() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String path = "/api/v1/projects/" + projectId + "/agent/asr/sessions/" + sessionId + "/audio";
        mvc.perform(post(path).contentType("application/octet-stream").content(new byte[] {1, 2}))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                .contentType("application/octet-stream").content(new byte[] {1, 2}))
                .andExpect(status().isNoContent());
        verify(service).append(eq(projectId), any(), eq(sessionId), eq(new byte[] {1, 2}));
    }

    @Test
    void voiceSessionRequiresBearerAndReturnsSessionId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String path = "/api/v1/projects/" + projectId + "/agent/asr/sessions";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        when(service.start(eq(projectId), any())).thenReturn(sessionId);
        mvc.perform(post(path).with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
    }
}
