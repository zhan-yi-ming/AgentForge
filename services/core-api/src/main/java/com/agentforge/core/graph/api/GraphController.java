package com.agentforge.core.graph.api;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.graph.application.GraphService;
import com.agentforge.core.graph.application.GraphCommands.*;
import com.agentforge.core.graph.domain.GraphModel.*;
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/graph")
public class GraphController {
    private final GraphService service;
    public GraphController(GraphService service) { this.service = service; }
    @GetMapping("/extraction/status")
    public com.agentforge.core.graph.application.GraphSourceSyncQueue.Status syncStatus(
        @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return service.syncStatus(projectId, AuthenticatedActor.from(jwt));
    }    @PostMapping("/extraction/rebuild")
    public org.springframework.http.ResponseEntity<Void> rebuild(@PathVariable UUID projectId,
        @AuthenticationPrincipal Jwt jwt) {
        service.rebuild(projectId, AuthenticatedActor.from(jwt));
        return org.springframework.http.ResponseEntity.accepted().build();
    }
    @DeleteMapping
    public org.springframework.http.ResponseEntity<Void> clear(@PathVariable UUID projectId,
        @AuthenticationPrincipal Jwt jwt, @RequestParam boolean confirm) {
        service.clear(projectId, AuthenticatedActor.from(jwt), confirm);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
    @PutMapping("/entities")
    public Entity put(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EntityRequest request) {
        return service.put(projectId, AuthenticatedActor.from(jwt), request);
    }
    @PutMapping("/relations")
    public Relation putRelation(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody RelationRequest request) {
        return service.putRelation(projectId, AuthenticatedActor.from(jwt), request);
    }
    @GetMapping("/entities/{entityId}/neighbors")
    public Page<Relation> neighbors(@PathVariable UUID projectId, @PathVariable UUID entityId,
        @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="") @Size(max=36) String after,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return service.neighbors(projectId, entityId, AuthenticatedActor.from(jwt), after, limit);
    }
    @GetMapping("/entities")
    public Page<Entity> entities(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue="") @Size(max=36) String after,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return service.entities(projectId, AuthenticatedActor.from(jwt), after, limit);
    }
}