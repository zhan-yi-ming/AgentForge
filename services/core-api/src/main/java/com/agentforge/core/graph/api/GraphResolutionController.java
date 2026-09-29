package com.agentforge.core.graph.api;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.agentforge.core.graph.application.GraphResolutionService;
import com.agentforge.core.security.AuthenticatedActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/graph/resolution")
public class GraphResolutionController {
    private final GraphResolutionService service;
    private final com.agentforge.core.graph.application.GraphResolutionDecisionService decisions;
    public GraphResolutionController(GraphResolutionService service,
        com.agentforge.core.graph.application.GraphResolutionDecisionService decisions) {
        this.service=service; this.decisions=decisions;
    }
    public record SuggestionRequest(@NotNull UUID entityId) {}
    @PostMapping("/suggestions")
    public GraphResolutionService.Suggestion suggest(@PathVariable UUID projectId,
        @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SuggestionRequest request) {
        return service.suggest(projectId,request.entityId(),AuthenticatedActor.from(jwt));
    }
    @PutMapping("/decisions/{entityId}")
    public com.agentforge.core.graph.application.GraphResolutionDecisionService.Decision confirm(
        @PathVariable UUID projectId, @PathVariable UUID entityId,
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody com.agentforge.core.graph.application.GraphResolutionDecisionService.ConfirmRequest request) {
        return decisions.confirm(projectId,entityId,AuthenticatedActor.from(jwt),request);
    }
    @GetMapping("/decisions/{entityId}")
    public com.agentforge.core.graph.application.GraphResolutionDecisionService.Decision decision(
        @PathVariable UUID projectId,@PathVariable UUID entityId,@AuthenticationPrincipal Jwt jwt) {
        return decisions.decision(projectId,entityId,AuthenticatedActor.from(jwt));
    }
    @DeleteMapping("/decisions/{entityId}")
    public org.springframework.http.ResponseEntity<Void> revert(
        @PathVariable UUID projectId,@PathVariable UUID entityId,
        @RequestParam Long expectedVersion,@AuthenticationPrincipal Jwt jwt) {
        decisions.revert(projectId,entityId,AuthenticatedActor.from(jwt),expectedVersion);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
    @GetMapping("/canonicals/{canonicalId}")
    public com.agentforge.core.graph.application.GraphResolutionDecisionService.CanonicalView canonical(
        @PathVariable UUID projectId,@PathVariable UUID canonicalId,@AuthenticationPrincipal Jwt jwt) {
        return decisions.canonicalView(projectId,canonicalId,AuthenticatedActor.from(jwt));
    }
    @PutMapping("/canonicals/{canonicalId}")
    public com.agentforge.core.graph.application.GraphResolutionDecisionService.CanonicalView updateCanonical(
        @PathVariable UUID projectId,@PathVariable UUID canonicalId,@AuthenticationPrincipal Jwt jwt,
        @RequestBody com.agentforge.core.graph.application.GraphResolutionDecisionService.UpdateCanonicalRequest request) {
        return decisions.updateCanonical(projectId,canonicalId,AuthenticatedActor.from(jwt),request);
    }
}
