package com.agentforge.core.user.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.shared.web.RequestIdFilter;
import com.agentforge.core.user.application.UserService;
import com.agentforge.core.user.application.UserView;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void createUserReturnsCreatedResource() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(userService.createUser(anyString(), anyString())).thenReturn(
                new UserView(userId, "owner@example.com", "Owner", now, now));

        mockMvc.perform(post("/api/v1/users")
                        .header(RequestIdFilter.HEADER_NAME, "test-request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","displayName":"Owner"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/" + userId))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "test-request-1"))
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("owner@example.com"));
    }

    @Test
    void createUserRejectsInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","displayName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://agentforge.local/problems/invalid-request"))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.displayName").exists());
    }

    @Test
    void getUserReturnsProblemDetailWhenMissing() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.getUser(userId)).thenThrow(new ResourceNotFoundException("User not found: " + userId));

        mockMvc.perform(get("/api/v1/users/{userId}", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("User not found: " + userId));
    }
}
