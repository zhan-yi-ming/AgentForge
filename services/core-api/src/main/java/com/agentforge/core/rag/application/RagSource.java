package com.agentforge.core.rag.application;

import java.util.UUID;

public record RagSource(
        String sourceType,
        UUID sourceId,
        long version,
        String title,
        String content) {
}
