package com.agentforge.core.rag.application;

import java.util.List;

public record RagSourceSnapshot(long snapshotVersion, boolean sourcesChanged, List<RagSource> sources) {
}
