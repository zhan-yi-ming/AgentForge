package com.agentforge.core.conversation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_message")
public class AgentMessage {
    @Id private UUID id;
    @Column(name = "conversation_id", nullable = false) private UUID conversationId;
    @Column(name = "sequence_number", nullable = false) private long sequence;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AgentMessageRole role;
    @Column(nullable = false) private String content;
    @Column(name = "sources_json", nullable = false) private String sourcesJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AgentMessage() { }

    public AgentMessage(UUID conversationId, long sequence, AgentMessageRole role,
            String content, String sourcesJson, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.conversationId = conversationId;
        this.sequence = sequence;
        this.role = role;
        this.content = content;
        this.sourcesJson = sourcesJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public long getSequence() { return sequence; }
    public AgentMessageRole getRole() { return role; }
    public String getContent() { return content; }
    public String getSourcesJson() { return sourcesJson; }
    public Instant getCreatedAt() { return createdAt; }
}
