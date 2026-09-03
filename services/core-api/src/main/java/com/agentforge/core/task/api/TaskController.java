package com.agentforge.core.task.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.task.application.TaskService;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    ResponseEntity<TaskResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTaskRequest request) {
        TaskResponse response = TaskResponse.from(taskService.create(
                projectId,
                AuthenticatedActor.from(jwt),
                request.title(),
                request.description(),
                request.status(),
                request.priority()));
        return ResponseEntity
                .created(URI.create("/api/v1/projects/" + projectId + "/tasks/" + response.id()))
                .body(response);
    }

    @GetMapping
    List<TaskResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return taskService.list(projectId, AuthenticatedActor.from(jwt))
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    @GetMapping("/{taskId}")
    TaskResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId) {
        return TaskResponse.from(taskService.get(projectId, taskId, AuthenticatedActor.from(jwt)));
    }

    @PutMapping("/{taskId}")
    TaskResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskRequest request) {
        return TaskResponse.from(taskService.update(
                projectId,
                taskId,
                AuthenticatedActor.from(jwt),
                request.title(),
                request.description(),
                request.status(),
                request.priority(),
                request.version()));
    }

    @DeleteMapping("/{taskId}")
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @RequestParam @PositiveOrZero long version) {
        taskService.delete(projectId, taskId, AuthenticatedActor.from(jwt), version);
        return ResponseEntity.noContent().build();
    }
}
