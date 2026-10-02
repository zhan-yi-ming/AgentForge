package com.agentforge.core.graph.api;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.agentforge.core.graph.application.GraphRetrievalService;
import com.agentforge.core.rag.application.CoreInternalAuthentication;

@RestController
@RequestMapping("/internal/v1/graph")
public class GraphRetrievalController {
    private final CoreInternalAuthentication authentication;
    private final GraphRetrievalService retrieval;

    public GraphRetrievalController(CoreInternalAuthentication authentication, GraphRetrievalService retrieval) {
        this.authentication = authentication;
        this.retrieval = retrieval;
    }

    public record Request(@NotNull UUID projectId, @NotNull UUID userId, boolean actorAdmin,
        @NotBlank @Size(max=128) String requestId, @NotBlank @Size(max=1000) String query) {}
    public record Response(UUID projectId, String requestId, java.util.List<GraphRetrievalService.Match> matches) {}

    @PostMapping("/retrieval")
    public Response retrieve(@RequestHeader(value="X-AgentForge-Core-Internal-Token", required=false) String token,
        @Valid @RequestBody Request request) {
        authentication.requireValid(token);
        return new Response(request.projectId(), request.requestId(),
            retrieval.retrieve(request.projectId(), request.userId(), request.actorAdmin(), request.query()));
    }
}
