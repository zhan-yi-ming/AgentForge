package com.agentforge.core.graph.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GraphResolutionAdvisor {
    private final RestClient client;
    public GraphResolutionAdvisor(RestClient agentServiceRestClient) { this.client=agentServiceRestClient; }
    public record Candidate(UUID entityId, String displayName) {}
    public record Advice(UUID recommendedCandidateId, double confidence) {}
    private record Request(UUID entityId, String displayName, String entityType, List<Candidate> candidates) {}
    private record Response(UUID recommendedCandidateId, Double confidence, Boolean reviewRequired) {}

    public Advice suggest(UUID entityId, String displayName, String entityType, List<Candidate> candidates) {
        if(candidates.isEmpty()) return new Advice(null,0);
        try {
            var response=client.post().uri("/internal/v1/graph/resolution/suggest")
                .body(new Request(entityId,displayName,entityType,candidates))
                .retrieve().body(Response.class);
            if(response==null || !Boolean.TRUE.equals(response.reviewRequired())
                || response.confidence()==null || !Double.isFinite(response.confidence())
                || response.confidence()<0 || response.confidence()>1
                || response.recommendedCandidateId()==null
                || candidates.stream().noneMatch(candidate -> candidate.entityId().equals(response.recommendedCandidateId())))
                return new Advice(null,0);
            return new Advice(response.recommendedCandidateId(),response.confidence());
        } catch(RestClientException | IllegalArgumentException failure) {
            return new Advice(null,0);
        }
    }
}
