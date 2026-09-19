package com.agentforge.core.agent.api;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.agentforge.core.agent.application.AgentAsrService;
import com.agentforge.core.security.AuthenticatedActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/asr/sessions")
public class AgentAsrController {
    private final AgentAsrService service;

    public AgentAsrController(AgentAsrService service) { this.service = service; }

    @PostMapping
    Map<String, UUID> start(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return Map.of("sessionId", service.start(projectId, AuthenticatedActor.from(jwt)));
    }

    @PostMapping(value = "/{sessionId}/audio", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void audio(@PathVariable UUID projectId, @PathVariable UUID sessionId,
               @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        byte[] chunk = request.getInputStream().readNBytes(64_001);
        if (chunk.length > 64_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Voice chunk is too large.");
        service.append(projectId, AuthenticatedActor.from(jwt), sessionId, chunk);
    }

    @GetMapping("/{sessionId}")
    AgentAsrService.Snapshot status(@PathVariable UUID projectId, @PathVariable UUID sessionId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return service.status(projectId, AuthenticatedActor.from(jwt), sessionId);
    }

    @PostMapping("/{sessionId}/finish")
    AgentAsrService.Snapshot finish(@PathVariable UUID projectId, @PathVariable UUID sessionId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return service.finish(projectId, AuthenticatedActor.from(jwt), sessionId);
    }

    @DeleteMapping("/{sessionId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID projectId, @PathVariable UUID sessionId,
                @AuthenticationPrincipal Jwt jwt) {
        service.cancel(projectId, AuthenticatedActor.from(jwt), sessionId);
    }
}
