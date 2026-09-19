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
import com.agentforge.core.agent.domain.AgentTaskActionRepository;
import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.conversation.domain.AgentConversation;
import com.agentforge.core.conversation.domain.AgentConversationRepository;
import com.agentforge.core.conversation.domain.AgentMessageRepository;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ConflictException;
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
                conversations, messages, mock(AgentTaskActionRepository.class), projects, new ObjectMapper(),
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

    @Test
    void deletingOwnedHistoryRemovesMessagesAndPreventsOldIdReuse() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        AgentConversation conversation = AgentConversation.start(conversationId, projectId, userId,
                "Private question", Instant.EPOCH);
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentTaskActionRepository actions = mock(AgentTaskActionRepository.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
        ConversationHistoryService service = new ConversationHistoryService(conversations, messages, actions,
                projects, new ObjectMapper(), Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC));

        service.delete(projectId, conversationId, actor);

        verify(messages).deleteAllByConversationId(conversationId);
        assertThat(conversation.isDeleted()).isTrue();
        assertThat(conversation.getPreview()).isEmpty();
        assertThatThrownBy(() -> service.appendCompletedExchange(projectId, actor, conversationId,
                "Again", "Answer", List.of())).isInstanceOf(ConflictException.class);
    }

    @Test
    void pendingActionBlocksHistoryDeletionWithoutRemovingMessages() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        AgentConversation conversation = AgentConversation.start(conversationId, projectId, userId,
                "Question", Instant.EPOCH);
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentTaskActionRepository actions = mock(AgentTaskActionRepository.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
        when(actions.existsByConversationAndStatusIn(projectId, userId, conversationId,
                List.of(AgentActionStatus.PENDING, AgentActionStatus.APPROVED))).thenReturn(true);
        ConversationHistoryService service = new ConversationHistoryService(conversations, messages, actions,
                projects, new ObjectMapper(), Clock.systemUTC());

        assertThatThrownBy(() -> service.delete(projectId, conversationId, actor))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(messages);
        assertThat(conversation.isDeleted()).isFalse();
    }

    @Test
    void anotherUsersConversationCannotBeDeleted() {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(
                AgentConversation.start(conversationId, projectId, UUID.randomUUID(), "Private", Instant.EPOCH)));
        ConversationHistoryService service = service(conversations, messages, projects);

        assertThatThrownBy(() -> service.delete(projectId, conversationId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(messages);
    }

    @Test
    void chatDoesNotRevealAnotherUsersConversationExistence() {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        when(conversations.findByIdForUpdate(conversationId)).thenReturn(Optional.of(
                AgentConversation.start(conversationId, projectId, UUID.randomUUID(), "Private", Instant.EPOCH)));
        ConversationHistoryService service = service(conversations, mock(AgentMessageRepository.class),
                mock(ProjectAccess.class));

        assertThatThrownBy(() -> service.requireWritable(projectId, conversationId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private ConversationHistoryService service(AgentConversationRepository conversations,
            AgentMessageRepository messages, ProjectAccess projects) {
        return new ConversationHistoryService(conversations, messages, mock(AgentTaskActionRepository.class),
                projects, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-08T06:00:00Z"), ZoneOffset.UTC));
    }
}
