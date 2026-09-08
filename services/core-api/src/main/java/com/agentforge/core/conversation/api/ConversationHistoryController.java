package com.agentforge.core.conversation.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.conversation.application.ConversationDetailView;
import com.agentforge.core.conversation.application.ConversationHistoryService;
import com.agentforge.core.conversation.application.ConversationSummaryView;
import com.agentforge.core.security.AuthenticatedActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/conversations")
public class ConversationHistoryController {
    private final ConversationHistoryService service;
    public ConversationHistoryController(ConversationHistoryService service) { this.service = service; }

    @GetMapping
    List<ConversationSummaryView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return service.list(projectId, AuthenticatedActor.from(jwt));
    }

    @GetMapping("/{conversationId}")
    ConversationDetailView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
            @PathVariable UUID conversationId) {
        return service.get(projectId, conversationId, AuthenticatedActor.from(jwt));
    }
}
