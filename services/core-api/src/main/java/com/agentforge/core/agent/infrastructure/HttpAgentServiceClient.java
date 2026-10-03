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
import org.springframework.web.client.RestClientResponseException;

import com.agentforge.core.agent.application.AgentChatResult;
import com.agentforge.core.agent.application.AgentAbortResult;
import com.agentforge.core.agent.application.AgentTaskType;
import com.agentforge.core.agent.application.AgentResumeResult;
import com.agentforge.core.agent.application.AgentServiceClient;
import com.agentforge.core.agent.application.AgentStreamEvent;
import com.agentforge.core.shared.error.ConflictException;
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
        stream(projectId, userId, actorAdmin, message, conversationId, requestId, AgentTaskType.ANSWER, sink);
    }

    @Override
    public void stream(UUID projectId, UUID userId, boolean actorAdmin, String message,
            UUID conversationId, String requestId, AgentTaskType taskType, Consumer<AgentStreamEvent> sink) {
        String effectiveRequestId = StringUtils.hasText(requestId)
                ? requestId
                : UUID.randomUUID().toString();
        try {
            restClient.post()
                    .uri("/internal/v1/chat/stream")
                    .header("X-Request-Id", effectiveRequestId)
                    .body(new InternalChatRequest(
                            projectId, userId, actorAdmin, message, conversationId, effectiveRequestId, taskType))
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
        return chat(projectId, userId, actorAdmin, message, conversationId, requestId, AgentTaskType.ANSWER);
    }

    @Override
    public AgentChatResult chat(UUID projectId, UUID userId, boolean actorAdmin, String message,
            UUID conversationId, String requestId, AgentTaskType taskType) {
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
                            effectiveRequestId, taskType))
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

    @Override
    public AgentResumeResult resume(
            UUID projectId,
            UUID checkpointUserId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionWorkflowId,
            UUID actionId,
            String decision,
            String idempotencyKey,
            String requestId) {
        try {
            AgentResumeResult response = restClient.post()
                    .uri("/internal/v1/agent/resume")
                    .header("X-Request-Id", requestId)
                    .body(new InternalResumeRequest(
                            projectId,
                            checkpointUserId,
                            actorAdmin,
                            conversationId,
                            actionWorkflowId,
                            actionId,
                            decision,
                            idempotencyKey,
                            requestId))
                    .retrieve()
                    .body(AgentResumeResult.class);
            if (response == null) {
                throw new ServiceUnavailableException("Agent Service returned an empty resume response.");
            }
            return response;
        }
        catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404
                    || exception.getStatusCode().value() == 409) {
                throw new ConflictException("Agent workflow cannot be resumed.");
            }
            throw new ServiceUnavailableException("Agent Service resume is unavailable.", exception);
        }
        catch (RestClientException exception) {
            throw new ServiceUnavailableException("Agent Service resume is unavailable.", exception);
        }
    }

    @Override
    public AgentAbortResult abort(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionWorkflowId,
            String requestId) {
        try {
            AgentAbortResult response = restClient.post()
                    .uri("/internal/v1/agent/abort")
                    .header("X-Request-Id", requestId)
                    .body(new InternalAbortRequest(
                            projectId, userId, actorAdmin, conversationId,
                            actionWorkflowId, requestId))
                    .retrieve()
                    .body(AgentAbortResult.class);
            if (response == null) {
                throw new ServiceUnavailableException("Agent Service returned an empty abort response.");
            }
            return response;
        }
        catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404
                    || exception.getStatusCode().value() == 409) {
                throw new ConflictException("Agent workflow cannot be aborted.");
            }
            throw new ServiceUnavailableException("Agent Service abort is unavailable.", exception);
        }
        catch (RestClientException exception) {
            throw new ServiceUnavailableException("Agent Service abort is unavailable.", exception);
        }
    }

    private record InternalChatRequest(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            String message,
            UUID conversationId,
            String requestId,
            AgentTaskType taskType) {
    }

    private record InternalResumeRequest(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionWorkflowId,
            UUID actionId,
            String decision,
            String idempotencyKey,
            String requestId) {
    }

    private record InternalAbortRequest(
            UUID projectId,
            UUID userId,
            boolean actorAdmin,
            UUID conversationId,
            UUID actionWorkflowId,
            String requestId) {
    }
}
