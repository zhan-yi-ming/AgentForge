package com.agentforge.core.project.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.project.application.ProjectView;
import com.agentforge.core.shared.error.ConflictException;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void createProjectReturnsCreatedResource() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(projectService.createProject(any(UUID.class), anyString(), any())).thenReturn(
                new ProjectView(projectId, ownerId, "AgentForge", null, now, now));

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","name":"AgentForge"}
                                """.formatted(ownerId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/projects/" + projectId))
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void createProjectMapsConflictToProblemDetail() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(projectService.createProject(any(UUID.class), anyString(), any())).thenThrow(
                new ConflictException("A project with this name already exists for the owner."));

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerId":"%s","name":"AgentForge"}
                                """.formatted(ownerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void listProjectsReturnsArray() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        when(projectService.listProjects(ownerId)).thenReturn(List.of(
                new ProjectView(UUID.randomUUID(), ownerId, "AgentForge", null, now, now)));

        mockMvc.perform(get("/api/v1/projects").param("ownerId", ownerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("AgentForge"));
    }
}
