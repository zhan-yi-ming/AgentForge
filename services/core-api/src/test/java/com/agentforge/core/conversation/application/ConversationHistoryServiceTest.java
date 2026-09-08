package com.agentforge.core.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.agentforge.core.agent.application.AgentSource;
import com.agentforge.core.conversation.domain.AgentConversation;
import com.agentforge.core.conversation.domain.AgentConversationRepository;
import com.agentforge.core.conversation.domain.AgentMessageRepository;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConversationHistoryServiceTest {

    @Test
    void completedExchangeCanBeReadBackInsideItsProjectAndUserScope() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.empty());
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messages.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConversationHistoryService service = new ConversationHistoryService(
                conversations, messages, projects, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-08T06:00:00Z"), ZoneOffset.UTC));

        service.appendCompletedExchange(
                projectId, actor, conversationId, "What changed?", "RBAC changed.",
                List.of(new AgentSource("WIKI", UUID.randomUUID(), "Security", "excerpt")));

        verify(projects).requireAccess(projectId, actor);
        verify(conversations).save(any(AgentConversation.class));
        verify(messages).saveAll(any());
    }

    @Test
    void existingConversationCannotBeReboundToAnotherProjectOrUser() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(
                AgentConversation.start(conversationId, UUID.randomUUID(), UUID.randomUUID(), "Original", Instant.now())));
        ConversationHistoryService service = service(conversations, messages, projects);

        assertThatThrownBy(() -> service.appendCompletedExchange(projectId,
                new AuthenticatedActor(userId, false), conversationId, "Question", "Answer", List.of()))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(messages);
    }

    @Test
    void detailLookupDoesNotRevealConversationOutsideTheExactScope() {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        when(conversations.findByScope(conversationId, projectId, actor.userId())).thenReturn(Optional.empty());
        ConversationHistoryService service = service(conversations, messages, projects);

        assertThatThrownBy(() -> service.get(projectId, conversationId, actor))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(messages);
    }

    private ConversationHistoryService service(AgentConversationRepository conversations,
            AgentMessageRepository messages, ProjectAccess projects) {
        return new ConversationHistoryService(conversations, messages, projects, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-08T06:00:00Z"), ZoneOffset.UTC));
    }
}
