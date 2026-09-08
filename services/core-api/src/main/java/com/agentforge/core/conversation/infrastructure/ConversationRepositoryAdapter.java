package com.agentforge.core.conversation.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.agentforge.core.conversation.domain.AgentConversation;
import com.agentforge.core.conversation.domain.AgentConversationRepository;
import com.agentforge.core.conversation.domain.AgentMessage;
import com.agentforge.core.conversation.domain.AgentMessageRepository;

@Repository
class ConversationRepositoryAdapter implements AgentConversationRepository {
    private final SpringConversationRepository repository;
    ConversationRepositoryAdapter(SpringConversationRepository repository) { this.repository = repository; }
    public Optional<AgentConversation> findByIdForUpdate(UUID id) { return repository.findByIdForUpdate(id); }
    public Optional<AgentConversation> findByScope(UUID id, UUID projectId, UUID userId) { return repository.findByIdAndProjectIdAndUserId(id, projectId, userId); }
    public List<AgentConversation> findAllByScope(UUID projectId, UUID userId) { return repository.findAllByProjectIdAndUserIdOrderByUpdatedAtDescIdAsc(projectId, userId); }
    public AgentConversation save(AgentConversation conversation) { return repository.save(conversation); }
}

interface SpringConversationRepository extends JpaRepository<AgentConversation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AgentConversation c where c.id = :id")
    Optional<AgentConversation> findByIdForUpdate(@Param("id") UUID id);
    Optional<AgentConversation> findByIdAndProjectIdAndUserId(UUID id, UUID projectId, UUID userId);
    List<AgentConversation> findAllByProjectIdAndUserIdOrderByUpdatedAtDescIdAsc(UUID projectId, UUID userId);
}

@Repository
class MessageRepositoryAdapter implements AgentMessageRepository {
    private final SpringMessageRepository repository;
    MessageRepositoryAdapter(SpringMessageRepository repository) { this.repository = repository; }
    public List<AgentMessage> saveAll(Iterable<AgentMessage> messages) { return repository.saveAll(messages); }
    public List<AgentMessage> findAllByConversationId(UUID conversationId) { return repository.findAllByConversationIdOrderBySequenceAsc(conversationId); }
}

interface SpringMessageRepository extends JpaRepository<AgentMessage, UUID> {
    List<AgentMessage> findAllByConversationIdOrderBySequenceAsc(UUID conversationId);
}
