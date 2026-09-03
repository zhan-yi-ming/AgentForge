package com.agentforge.core.wiki.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.wiki.domain.WikiPage;

public record WikiPageView(
        UUID id,
        UUID projectId,
        String title,
        String content,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static WikiPageView from(WikiPage page) {
        return new WikiPageView(
                page.getId(),
                page.getProjectId(),
                page.getTitle(),
                page.getContent(),
                page.getVersion(),
                page.getCreatedAt(),
                page.getUpdatedAt());
    }
}
