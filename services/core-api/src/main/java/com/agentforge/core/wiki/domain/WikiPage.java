package com.agentforge.core.wiki.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "wiki_page",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_wiki_page_project_title",
                columnNames = {"project_id", "title"}))
public class WikiPage {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WikiPage() {
    }

    private WikiPage(UUID id, UUID projectId, String title, String content, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.projectId = Objects.requireNonNull(projectId);
        this.title = Objects.requireNonNull(title);
        this.content = Objects.requireNonNull(content);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static WikiPage create(UUID projectId, String title, String content, Instant now) {
        return new WikiPage(UUID.randomUUID(), projectId, title, content, now);
    }

    public void update(String title, String content, Instant now) {
        this.title = Objects.requireNonNull(title);
        this.content = Objects.requireNonNull(content);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
