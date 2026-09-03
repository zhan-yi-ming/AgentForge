package com.agentforge.core.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.agentforge.core.project.api.ProjectController;
import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.project.application.ProjectView;
import com.agentforge.core.security.api.AuthenticationController;
import com.agentforge.core.security.application.AuthenticationResult;
import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.security.application.IssuedToken;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.web.RequestIdFilter;
import com.agentforge.core.user.UserAccount;
import com.agentforge.core.user.UserRole;
import com.agentforge.core.user.api.UserController;
import com.agentforge.core.user.application.UserService;
import com.agentforge.core.user.application.UserView;

@WebMvcTest({AuthenticationController.class, ProjectController.class, UserController.class})
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M"
})
class ApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private UserService userService;

    @Test
    void registrationIsPublicAndNeverReturnsPasswordMaterial() throws Exception {
        UserAccount user = userAccount();
        when(authenticationService.register(anyString(), anyString(), anyString()))
                .thenReturn(new AuthenticationResult(user, new IssuedToken("signed-token", 1800)));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"owner@example.com",
                                  "displayName":"Owner",
                                  "password":"correct-horse-battery"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/me"))
                .andExpect(jsonPath("$.accessToken").value("signed-token"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void businessApiWithoutBearerTokenReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header(RequestIdFilter.HEADER_NAME, "security-test-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "security-test-1"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").value("security-test-1"));
        verify(projectService, never()).listProjects(any());
    }

    @Test
    void onlyDocumentedAuthenticationEndpointsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/internal"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedProjectRequestUsesJwtSubjectInsteadOfRequestOwner() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(projectService.createProject(any(AuthenticatedActor.class), anyString(), any()))
                .thenReturn(new ProjectView(projectId, userId, "AgentForge", null, now, now));

        mockMvc.perform(post("/api/v1/projects")
                        .with(jwt().jwt(token -> token
                                .subject(userId.toString())
                                .claim("roles", List.of("USER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"AgentForge"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(userId.toString()));
    }

    @Test
    void applicationOwnershipFailureMapsToForbiddenProblem() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectService.getProject(any(), any()))
                .thenThrow(new ForbiddenException("The authenticated user cannot access this project."));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(jwt().jwt(token -> token
                                .subject(userId.toString())
                                .claim("roles", List.of("USER")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(
                        "The authenticated user cannot access this project."));
    }

    @Test
    void currentUserComesFromJwtSubject() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(userService.getUser(userId)).thenReturn(new UserView(
                userId,
                "owner@example.com",
                "Owner",
                UserRole.USER,
                now,
                now));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(token -> token
                                .subject(userId.toString())
                                .claim("roles", List.of("USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    private UserAccount userAccount() {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        return new UserAccount(
                UUID.randomUUID(),
                "owner@example.com",
                "Owner",
                "{bcrypt}not-returned",
                UserRole.USER,
                now,
                now);
    }
}
