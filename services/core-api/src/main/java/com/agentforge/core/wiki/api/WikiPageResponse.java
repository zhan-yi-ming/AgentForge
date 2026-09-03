package com.agentforge.core.wiki.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.wiki.application.WikiPageView;

public record WikiPageResponse(
        UUID id,
        UUID projectId,
        String title,
        String content,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static WikiPageResponse from(WikiPageView page) {
        return new WikiPageResponse(
                page.id(),
                page.projectId(),
                page.title(),
                page.content(),
                page.version(),
                page.createdAt(),
                page.updatedAt());
    }
}
