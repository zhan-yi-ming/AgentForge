package com.agentforge.core.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_conversation")
public class AgentConversation {

    @Id
    private UUID id;
    @Column(name = "project_id", nullable = false)
    private UUID projectId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false, length = 240)
    private String preview;
    @Column(name = "message_count", nullable = false)
    private int messageCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentConversation() {
    }

    private AgentConversation(UUID id, UUID projectId, UUID userId, String preview, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.projectId = Objects.requireNonNull(projectId);
        this.userId = Objects.requireNonNull(userId);
        this.preview = Objects.requireNonNull(preview);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static AgentConversation start(UUID id, UUID projectId, UUID userId, String question, Instant now) {
        String normalized = question.strip();
        int codePointCount = normalized.codePointCount(0, normalized.length());
        int end = normalized.offsetByCodePoints(0, Math.min(codePointCount, 240));
        return new AgentConversation(id, projectId, userId, normalized.substring(0, end), now);
    }

    public long nextSequence() { return messageCount; }
    public void appendedExchange(Instant now) { messageCount += 2; updatedAt = now; }
    public boolean belongsTo(UUID projectId, UUID userId) {
        return this.projectId.equals(projectId) && this.userId.equals(userId);
    }
    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getUserId() { return userId; }
    public String getPreview() { return preview; }
    public int getMessageCount() { return messageCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
