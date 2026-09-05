package com.agentforge.core.agent.infrastructure;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
            boolean actorAdmin,
            String message,
            UUID conversationId,
            String requestId) {
        String effectiveRequestId = StringUtils.hasText(requestId)
                ? requestId
                : UUID.randomUUID().toString();
        try {
            AgentChatResult response = restClient.post()
                    .uri("/internal/v1/chat")
                    .header("X-Request-Id", effectiveRequestId)
                    .body(new InternalChatRequest(
                            projectId,
                            userId,
                            actorAdmin,
                            message,
                            conversationId,
                            effectiveRequestId))
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
            boolean actorAdmin,
            String message,
            UUID conversationId,
            String requestId) {
    }
}
