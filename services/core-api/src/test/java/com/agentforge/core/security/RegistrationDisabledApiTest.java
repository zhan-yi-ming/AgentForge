package com.agentforge.core.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.security.api.AuthenticationController;
import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.shared.web.RequestIdFilter;

@WebMvcTest(AuthenticationController.class)
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", // gitleaks:allow public test-only key
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M",
    "agentforge.security.registration-enabled=false"
})
class RegistrationDisabledApiTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AuthenticationService authenticationService;

    @Test
    void productionRegistrationCanBeDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"owner@example.com",
                                  "displayName":"Owner",
                                  "password":"correct-horse-battery"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://agentforge.local/problems/forbidden"));
        verify(authenticationService, never()).register(anyString(), anyString(), anyString());
    }
}
