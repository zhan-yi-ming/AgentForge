package com.agentforge.core.agent.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ServiceUnavailableException;

@Service
public class AgentAsrService {
    private final ProjectAccess projectAccess;
    private final AiUsageQuota aiUsageQuota;
    private final RestClient agentServiceRestClient;

    public AgentAsrService(ProjectAccess projectAccess, AiUsageQuota aiUsageQuota, RestClient agentServiceRestClient) {
        this.projectAccess = projectAccess;
        this.aiUsageQuota = aiUsageQuota;
        this.agentServiceRestClient = agentServiceRestClient;
    }

    public UUID start(UUID projectId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        try {
            StartResponse result = agentServiceRestClient.post().uri("/internal/v1/asr/sessions")
                    .body(new Scope(projectId, actor.userId()))
                    .retrieve().body(StartResponse.class);
            if (result == null || result.sessionId() == null) throw new ServiceUnavailableException("Voice recognition is unavailable.");
            try {
                aiUsageQuota.consume(actor.userId());
            } catch (RuntimeException exception) {
                try {
                    agentServiceRestClient.delete().uri(uri -> uri.path("/internal/v1/asr/sessions/{sessionId}")
                            .queryParam("projectId", projectId).queryParam("userId", actor.userId())
                            .build(result.sessionId())).retrieve().toBodilessEntity();
                } catch (RestClientException ignored) { /* quota failure still takes precedence */ }
                throw exception;
            }
            return result.sessionId();
        }
        catch (RestClientException exception) { throw unavailable(exception); }
    }

    public void append(UUID projectId, AuthenticatedActor actor, UUID sessionId, byte[] audio) {
        projectAccess.requireAccess(projectId, actor);
        if (audio.length == 0 || audio.length > 64_000 || audio.length % 2 != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid voice chunk.");
        }
        try {
            agentServiceRestClient.post().uri(uri -> uri.path("/internal/v1/asr/sessions/{sessionId}/audio")
                    .queryParam("projectId", projectId).queryParam("userId", actor.userId()).build(sessionId))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM).body(audio).retrieve().toBodilessEntity();
        }
        catch (RestClientException exception) { throw unavailable(exception); }
    }

    public Snapshot status(UUID projectId, AuthenticatedActor actor, UUID sessionId) {
        projectAccess.requireAccess(projectId, actor);
        try {
            Snapshot result = agentServiceRestClient.get().uri(uri -> uri.path("/internal/v1/asr/sessions/{sessionId}")
                    .queryParam("projectId", projectId).queryParam("userId", actor.userId()).build(sessionId))
                    .retrieve().body(Snapshot.class);
            if (result == null) throw new ServiceUnavailableException("Voice recognition is unavailable.");
            return result;
        }
        catch (RestClientException exception) { throw unavailable(exception); }
    }

    public Snapshot finish(UUID projectId, AuthenticatedActor actor, UUID sessionId) {
        projectAccess.requireAccess(projectId, actor);
        try {
            Snapshot result = agentServiceRestClient.post().uri("/internal/v1/asr/sessions/{sessionId}/finish", sessionId)
                    .body(new Scope(projectId, actor.userId())).retrieve().body(Snapshot.class);
            if (result == null) throw new ServiceUnavailableException("Voice recognition is unavailable.");
            return result;
        }
        catch (RestClientException exception) { throw unavailable(exception); }
    }

    public void cancel(UUID projectId, AuthenticatedActor actor, UUID sessionId) {
        projectAccess.requireAccess(projectId, actor);
        try {
            agentServiceRestClient.delete().uri(uri -> uri.path("/internal/v1/asr/sessions/{sessionId}")
                    .queryParam("projectId", projectId).queryParam("userId", actor.userId()).build(sessionId))
                    .retrieve().toBodilessEntity();
        }
        catch (RestClientException exception) { throw unavailable(exception); }
    }

    private RuntimeException unavailable(RestClientException exception) {
        if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice session was not found.");
        }
        return new ServiceUnavailableException("Voice recognition is unavailable.", exception);
    }

    private record Scope(UUID projectId, UUID userId) {}
    private record StartResponse(UUID sessionId) {}
    public record Snapshot(UUID sessionId, String text, boolean finished) {}
}
