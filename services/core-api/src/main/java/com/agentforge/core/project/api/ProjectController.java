package com.agentforge.core.project.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.project.application.ProjectService;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = ProjectResponse.from(projectService.createProject(
                request.ownerId(),
                request.name(),
                request.description()));
        return ResponseEntity
                .created(URI.create("/api/v1/projects/" + response.id()))
                .body(response);
    }

    @GetMapping("/{projectId}")
    ProjectResponse getProject(@PathVariable UUID projectId) {
        return ProjectResponse.from(projectService.getProject(projectId));
    }

    @GetMapping
    List<ProjectResponse> listProjects(@RequestParam UUID ownerId) {
        return projectService.listProjects(ownerId)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }
}
