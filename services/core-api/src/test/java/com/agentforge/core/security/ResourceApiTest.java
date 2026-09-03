package com.agentforge.core.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.web.RequestIdFilter;
import com.agentforge.core.task.api.TaskController;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;
import com.agentforge.core.wiki.api.WikiPageController;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.wiki.application.WikiPageView;

@WebMvcTest({WikiPageController.class, TaskController.class})
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M"
})
class ResourceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WikiPageService wikiPageService;

    @MockitoBean
    private TaskService taskService;

    @Test
    void wikiCreateReturnsNestedLocationAndVersion() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(wikiPageService.create(eq(projectId), any(), anyString(), anyString()))
                .thenReturn(new WikiPageView(pageId, projectId, "Architecture", "# System", 0, now, now));

        mockMvc.perform(post("/api/v1/projects/{projectId}/wiki-pages", projectId)
                        .with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Architecture","content":"# System"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/projects/" + projectId + "/wiki-pages/" + pageId))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void wikiUpdateMapsStaleVersionToConflict() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        when(wikiPageService.update(eq(projectId), eq(pageId), any(), anyString(), anyString(), eq(3L)))
                .thenThrow(new ConflictException("The Wiki page version is stale."));

        mockMvc.perform(put("/api/v1/projects/{projectId}/wiki-pages/{pageId}", projectId, pageId)
                        .with(userJwt(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Architecture","content":"new","version":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The Wiki page version is stale."));
    }

    @Test
    void wikiDeleteRequiresAndPassesVersion() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/projects/{projectId}/wiki-pages/{pageId}", projectId, pageId)
                        .with(userJwt(UUID.randomUUID()))
                        .param("version", "2"))
                .andExpect(status().isNoContent());

        verify(wikiPageService).delete(eq(projectId), eq(pageId), any(), eq(2L));
    }

    @Test
    void taskCreateReturnsDocumentedDefaults() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(taskService.create(eq(projectId), any(), anyString(), any(), any(), any()))
                .thenReturn(new TaskView(
                        taskId,
                        projectId,
                        "First task",
                        null,
                        TaskStatus.TODO,
                        TaskPriority.MEDIUM,
                        0,
                        now,
                        now));

        mockMvc.perform(post("/api/v1/projects/{projectId}/tasks", projectId)
                        .with(userJwt(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"First task"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
    }

    @Test
    void taskListReturnsArray() throws Exception {
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(taskService.list(eq(projectId), any())).thenReturn(List.of(new TaskView(
                UUID.randomUUID(),
                projectId,
                "First task",
                null,
                TaskStatus.IN_PROGRESS,
                TaskPriority.HIGH,
                1,
                now,
                now)));

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks", projectId)
                        .with(userJwt(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"));
    }

    @Test
    void taskRejectsUnknownStatus() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/tasks", UUID.randomUUID())
                        .with(userJwt(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"First task","status":"BLOCKED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://agentforge.local/problems/invalid-request"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(UUID userId) {
        return jwt().jwt(token -> token
                .subject(userId.toString())
                .claim("roles", List.of("USER")));
    }
}
