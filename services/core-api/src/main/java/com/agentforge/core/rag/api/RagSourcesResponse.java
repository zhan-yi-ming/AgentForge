package com.agentforge.core.rag.api;

import java.util.List;
import java.util.UUID;

import com.agentforge.core.rag.application.RagSource;

public record RagSourcesResponse(UUID projectId, List<RagSource> sources, String requestId) {
}
