package com.agentforge.core.agent.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.agentforge.core.agent.application.AgentActionService;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.web.RequestIdFilter;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/agent/actions")
public class AgentActionController {

    private final AgentActionService agentActionService;

    public AgentActionController(AgentActionService agentActionService) {
        this.agentActionService = agentActionService;
    }

    @PostMapping("/{actionId}/confirm")
    AgentActionResponse confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID actionId,
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 100)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            HttpServletRequest servletRequest) {
        return AgentActionResponse.from(agentActionService.confirm(
                projectId,
                actionId,
                AuthenticatedActor.from(jwt),
                idempotencyKey,
                requestId(servletRequest)));
    }

    @PostMapping("/{actionId}/reject")
    AgentActionResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID actionId,
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 100)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            HttpServletRequest servletRequest) {
        return AgentActionResponse.from(agentActionService.reject(
                projectId,
                actionId,
                AuthenticatedActor.from(jwt),
                idempotencyKey,
                requestId(servletRequest)));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
