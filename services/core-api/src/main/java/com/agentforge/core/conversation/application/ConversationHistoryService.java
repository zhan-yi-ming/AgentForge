package com.agentforge.core.conversation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agentforge.core.agent.application.AgentSource;
import com.agentforge.core.conversation.domain.AgentConversation;
import com.agentforge.core.conversation.domain.AgentConversationRepository;
import com.agentforge.core.conversation.domain.AgentMessage;
import com.agentforge.core.conversation.domain.AgentMessageRepository;
import com.agentforge.core.conversation.domain.AgentMessageRole;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ConversationHistoryService {
    private final AgentConversationRepository conversations;
    private final AgentMessageRepository messages;
    private final ProjectAccess projectAccess;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConversationHistoryService(AgentConversationRepository conversations, AgentMessageRepository messages,
            ProjectAccess projectAccess, ObjectMapper objectMapper, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.projectAccess = projectAccess;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void appendCompletedExchange(UUID projectId, AuthenticatedActor actor, UUID conversationId,
            String question, String answer, List<AgentSource> sources) {
        projectAccess.requireAccess(projectId, actor);
        Instant now = Instant.now(clock);
        AgentConversation conversation = conversations.findByIdForUpdate(conversationId).orElse(null);
        if (conversation == null) {
            conversation = conversations.save(
                    AgentConversation.start(conversationId, projectId, actor.userId(), question, now));
        }
        if (!conversation.belongsTo(projectId, actor.userId())) {
            throw new ForbiddenException("The conversation belongs to another scope.");
        }
        long sequence = conversation.nextSequence();
        messages.saveAll(List.of(
                new AgentMessage(conversationId, sequence, AgentMessageRole.USER, question, "[]", now),
                new AgentMessage(conversationId, sequence + 1, AgentMessageRole.ASSISTANT, answer,
                        writeSources(sources), now)));
        conversation.appendedExchange(now);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryView> list(UUID projectId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        return conversations.findAllByScope(projectId, actor.userId()).stream()
                .map(ConversationSummaryView::from).toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailView get(UUID projectId, UUID conversationId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        AgentConversation conversation = conversations.findByScope(conversationId, projectId, actor.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));
        List<ConversationMessageView> messageViews = messages.findAllByConversationId(conversationId).stream()
                .map(message -> new ConversationMessageView(message.getRole(), message.getContent(),
                        readSources(message.getSourcesJson()), message.getCreatedAt()))
                .toList();
        return new ConversationDetailView(conversation.getId(), conversation.getPreview(),
                conversation.getMessageCount(), conversation.getCreatedAt(), conversation.getUpdatedAt(), messageViews);
    }

    private String writeSources(List<AgentSource> sources) {
        try { return objectMapper.writeValueAsString(sources == null ? List.of() : sources); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to save conversation sources.", exception); }
    }

    private List<AgentSource> readSources(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<AgentSource>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to read conversation sources.", exception); }
    }
}
