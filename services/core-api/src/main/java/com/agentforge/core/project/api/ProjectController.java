package com.agentforge.core.project.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.security.AuthenticatedActor;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    ResponseEntity<ProjectResponse> createProject(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = ProjectResponse.from(projectService.createProject(
                AuthenticatedActor.from(jwt),
                request.name(),
                request.description()));
        return ResponseEntity
                .created(URI.create("/api/v1/projects/" + response.id()))
                .body(response);
    }

    @GetMapping("/{projectId}")
    ProjectResponse getProject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return ProjectResponse.from(projectService.getProject(projectId, AuthenticatedActor.from(jwt)));
    }

    @GetMapping
    List<ProjectResponse> listProjects(@AuthenticationPrincipal Jwt jwt) {
        return projectService.listProjects(AuthenticatedActor.from(jwt))
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }
}
