package com.agentforge.core.agent.infrastructure;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.agentforge.core.agent.application.AgentChatResult;
import com.agentforge.core.agent.application.AgentServiceClient;
import com.agentforge.core.shared.error.ServiceUnavailableException;

@Component
public class HttpAgentServiceClient implements AgentServiceClient {

    private final RestClient restClient;

    public HttpAgentServiceClient(RestClient agentServiceRestClient) {
        this.restClient = agentServiceRestClient;
    }

    @Override
    public AgentChatResult chat(
            UUID projectId,
            UUID userId,
            String message,
            UUID conversationId,
            String requestId) {
        try {
            AgentChatResult response = restClient.post()
                    .uri("/internal/v1/chat")
                    .header("X-Request-Id", requestId)
                    .body(new InternalChatRequest(projectId, userId, message, conversationId, requestId))
                    .retrieve()
                    .body(AgentChatResult.class);
            if (response == null) {
                throw new ServiceUnavailableException("Agent Service returned an empty response.");
            }
            return response;
        }
        catch (RestClientException exception) {
            throw new ServiceUnavailableException("Agent Service is unavailable.", exception);
        }
    }

    private record InternalChatRequest(
            UUID projectId,
            UUID userId,
            String message,
            UUID conversationId,
            String requestId) {
    }
}
