package com.agentforge.core.rag.application;

import java.util.List;

public record RagSourceSnapshot(long snapshotVersion, List<RagSource> sources) {
}
