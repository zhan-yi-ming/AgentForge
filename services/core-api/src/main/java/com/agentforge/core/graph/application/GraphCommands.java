package com.agentforge.core.graph.application;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.agentforge.core.graph.domain.GraphModel.*;

public final class GraphCommands {
    private GraphCommands() {}
    public record EvidenceRequest(@NotNull @Valid Source source, @PositiveOrZero Integer chunkIndex,
        @NotNull @PositiveOrZero Integer start, @NotNull @Positive Integer end,
        @NotBlank @Size(max=2000) String excerpt, @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence,
        @NotNull @PositiveOrZero @JsonDeserialize(using=StrictVersion.class) Long expectedVersion) {}
    public record RelationRequest(@NotNull RelationType type, @NotNull java.util.UUID fromId,
        @NotNull java.util.UUID toId, @NotNull @Valid EvidenceRequest evidence) {}
    public record EntityRequest(@NotNull EntityType type, @NotBlank @Size(max=200) String externalId,
        @NotBlank @Size(max=200) String displayName, @Valid Source source,
        @NotNull @PositiveOrZero @JsonDeserialize(using=StrictVersion.class) Long expectedVersion) {}
}