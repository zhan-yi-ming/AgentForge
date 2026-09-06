package com.agentforge.core.agent.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.agentforge.core.agent.application.AgentChatResult;
import com.agentforge.core.agent.application.AgentServiceClient;
import com.agentforge.core.agent.application.AgentStreamEvent;
import com.agentforge.core.shared.error.ServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class HttpAgentServiceClient implements AgentServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpAgentServiceClient(RestClient agentServiceRestClient, ObjectMapper objectMapper) {
        this.restClient = agentServiceRestClient;
        this.objectMapper = objectMapper;
    }

    HttpAgentServiceClient(RestClient agentServiceRestClient) {
        this(agentServiceRestClient, new ObjectMapper().findAndRegisterModules());
    }

    @Override
    public void stream(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            String message,
            UUID conversationId,
            String requestId,
            Consumer<AgentStreamEvent> sink) {
        String effectiveRequestId = StringUtils.hasText(requestId)
                ? requestId
                : UUID.randomUUID().toString();
        try {
            restClient.post()
                    .uri("/internal/v1/chat/stream")
                    .header("X-Request-Id", effectiveRequestId)
                    .body(new InternalChatRequest(
                            projectId, userId, actorAdmin, message, conversationId, effectiveRequestId))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new ServiceUnavailableException("Agent Service is unavailable.");
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.isBlank()) {
                                    sink.accept(objectMapper.readValue(line, AgentStreamEvent.class));
                                }
                            }
                        }
                        catch (IOException exception) {
                            throw new ServiceUnavailableException("Agent Service stream is unavailable.", exception);
                        }
                        return null;
                    });
        }
        catch (RestClientException exception) {
            throw new ServiceUnavailableException("Agent Service is unavailable.", exception);
        }
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
