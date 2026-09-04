package com.agentforge.core.agent.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.agent.application.AgentChatService;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.web.RequestIdFilter;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentChatController {

    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
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
}
