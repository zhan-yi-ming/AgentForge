package com.agentforge.core.graph.domain;
import java.util.UUID;
import com.agentforge.core.graph.domain.GraphModel.*;
public interface GraphStore {
    void clear(UUID projectId);
    void replaceSource(UUID projectId, GraphModel.SourceType sourceType, UUID sourceId, GraphExtraction.Projection projection);
    Entity put(Entity entity, long expectedVersion);
    java.util.Optional<Entity> entity(UUID projectId, UUID id);
    Relation putRelation(Relation relation, long expectedEvidenceVersion);
    Page<Relation> neighbors(UUID projectId, UUID entityId, String after, int limit);
    Page<Entity> entities(UUID projectId, String after, int limit);
}
