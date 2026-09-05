package com.agentforge.core.rag.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.rag.application.CoreInternalAuthentication;
import com.agentforge.core.rag.application.RagSourceService;

@RestController
@RequestMapping("/internal/v1/rag")
public class RagSourcesController {

    private final CoreInternalAuthentication authentication;
    private final RagSourceService ragSourceService;

    public RagSourcesController(
            CoreInternalAuthentication authentication,
            RagSourceService ragSourceService) {
        this.authentication = authentication;
        this.ragSourceService = ragSourceService;
    }

    @PostMapping("/sources")
    RagSourcesResponse sources(
            @RequestHeader(value = "X-AgentForge-Core-Internal-Token", required = false) String token,
            @Valid @RequestBody RagSourcesRequest request) {
        authentication.requireValid(token);
        return new RagSourcesResponse(
                request.projectId(),
                ragSourceService.list(request.projectId(), request.userId(), request.actorAdmin()),
                request.requestId());
    }
}
