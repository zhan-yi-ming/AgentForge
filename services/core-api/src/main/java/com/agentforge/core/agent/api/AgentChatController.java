package com.agentforge.core.agent.api;

import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.agentforge.core.agent.application.AgentChatService;
import com.agentforge.core.agent.application.AgentChatCommand;
import com.agentforge.core.agent.application.AgentStreamEvent;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.web.RequestIdFilter;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final ObjectWriter sseJsonWriter;

    public AgentChatController(AgentChatService agentChatService, ObjectMapper objectMapper) {
        this.agentChatService = agentChatService;
        this.sseJsonWriter = objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII);
    }

    @PostMapping("/chat")
    AgentChatResponse chat(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody AgentChatRequest request,
            HttpServletRequest servletRequest) {
        String requestId = (String) servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return AgentChatResponse.from(agentChatService.chat(
                projectId,
                AuthenticatedActor.from(jwt),
                request.message(),
                request.conversationId(),
                requestId));
    }

    @PostMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    SseEmitter chatStream(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody AgentChatRequest request,
            HttpServletRequest servletRequest) {
        String requestId = (String) servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        AgentChatCommand command = agentChatService.prepareStream(
                projectId,
                AuthenticatedActor.from(jwt),
                request.message(),
                request.conversationId(),
                requestId);
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread.startVirtualThread(() -> {
            try {
                agentChatService.stream(command, event -> send(emitter, event));
                emitter.complete();
            }
            catch (RuntimeException exception) {
                send(emitter, new AgentStreamEvent(
                        "error", null, null, java.util.List.of(), null, null, null,
                        "AI service is temporarily unavailable."));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AgentStreamEvent event) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            if ("metadata".equals(event.type())) {
                data.put("conversationId", event.conversationId());
                data.put("requestId", event.requestId());
                data.put("sources", event.sources());
            }
            else if ("delta".equals(event.type())) {
                data.put("text", event.text());
            }
            else if ("complete".equals(event.type())) {
                data.put("pendingAction", event.pendingAction() == null
                        ? null
                        : AgentActionResponse.from(event.pendingAction()));
            }
            else {
                data.put("message", event.message());
            }
            emitter.send(SseEmitter.event().name(event.type()).data(sseJsonWriter.writeValueAsString(data)));
        }
        catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to write Agent stream.", exception);
        }
    }
}
