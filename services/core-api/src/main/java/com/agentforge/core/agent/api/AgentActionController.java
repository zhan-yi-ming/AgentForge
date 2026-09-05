package com.agentforge.core.agent.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.agent.application.AgentActionService;
import com.agentforge.core.security.AuthenticatedActor;

@RestController
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
            @PathVariable UUID actionId) {
        return AgentActionResponse.from(agentActionService.confirm(
                projectId,
                actionId,
                AuthenticatedActor.from(jwt)));
    }

    @PostMapping("/{actionId}/reject")
    AgentActionResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID actionId) {
        return AgentActionResponse.from(agentActionService.reject(
                projectId,
                actionId,
                AuthenticatedActor.from(jwt)));
    }
}
