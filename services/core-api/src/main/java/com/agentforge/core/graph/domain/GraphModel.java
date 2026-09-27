package com.agentforge.core.graph.domain;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonCreator;

public final class GraphModel {
    private GraphModel() {}
    public enum EntityType {
        PROJECT, SERVICE, API, WIKI, TASK, ISSUE;
        @JsonCreator public static EntityType parse(String value) { return valueOf(value); }
    }
    public enum SourceType {
        WIKI, TASK;
        @JsonCreator public static SourceType parse(String value) { return valueOf(value); }
    }
    public record Source(@NotNull SourceType type, @NotNull UUID id, @NotNull @PositiveOrZero @JsonDeserialize(using=StrictVersion.class) Long version) {}
    public record Entity(UUID id, UUID projectId, EntityType type, String externalId,
        String displayName, Source source, long version) {}
    public enum RelationType {
        CONTAINS, EXPOSES, DESCRIBES, MODIFIES, AFFECTS;
        @JsonCreator public static RelationType parse(String value) { return valueOf(value); }
        public boolean allows(EntityType from, EntityType to) {
            return switch(this) {
                case CONTAINS -> from == EntityType.PROJECT && to != EntityType.PROJECT;
                case EXPOSES -> from == EntityType.SERVICE && to == EntityType.API;
                case DESCRIBES -> from == EntityType.WIKI && java.util.Set.of(EntityType.SERVICE,EntityType.API,EntityType.TASK,EntityType.ISSUE).contains(to);
                case MODIFIES -> from == EntityType.TASK && java.util.Set.of(EntityType.SERVICE,EntityType.API,EntityType.WIKI).contains(to);
                case AFFECTS -> from == EntityType.ISSUE && java.util.Set.of(EntityType.SERVICE,EntityType.API,EntityType.TASK).contains(to);
            };
        }
    }
    public record Evidence(UUID id, UUID projectId, Source source, Integer chunkIndex,
        int start, int end, String excerpt, double confidence, long version) {}
    public record Relation(UUID id, UUID projectId, RelationType type, UUID fromId, UUID toId,
        List<Evidence> evidence, boolean hasMoreEvidence) {}
    public record Page<T>(List<T> items, String nextAfter) {}
    public static final class StrictVersion extends StdDeserializer<Long> {
        public StrictVersion() { super(Long.class); }
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}