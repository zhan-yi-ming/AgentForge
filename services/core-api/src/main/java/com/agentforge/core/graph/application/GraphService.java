package com.agentforge.core.graph.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import jakarta.validation.ConstraintViolationException;
import org.springframework.stereotype.Service;
import com.agentforge.core.graph.application.GraphCommands.*;
import com.agentforge.core.graph.domain.GraphModel.*;
import com.agentforge.core.graph.domain.GraphStore;
import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;

@Service
public class GraphService {
    private final ProjectService projects;
    private final WikiPageService wiki;
    private final TaskService tasks;
    private final GraphStore store;
    private final GraphSourceSyncQueue syncQueue;
    private final GraphProjectLifecycle lifecycle;
    public GraphService(ProjectService projects, WikiPageService wiki, TaskService tasks, GraphStore store,
        GraphSourceSyncQueue syncQueue, GraphProjectLifecycle lifecycle) {
        this.projects = projects; this.wiki = wiki; this.tasks = tasks; this.store = store;
        this.syncQueue = syncQueue; this.lifecycle = lifecycle;
    }
    public GraphSourceSyncQueue.Status syncStatus(UUID projectId, AuthenticatedActor actor) {
        projects.requireAccess(projectId, actor);
        return syncQueue.status(projectId);
    }    @org.springframework.transaction.annotation.Transactional
    public void rebuild(UUID projectId, AuthenticatedActor actor) {
        projects.requireAccess(projectId, actor);
        syncQueue.rebuild(projectId);
    }
    public void clear(UUID projectId, AuthenticatedActor actor, boolean confirm) {
        projects.requireAccess(projectId, actor);
        if (!confirm) throw invalid();
        long generation=lifecycle.beginClear(projectId);
        try { store.clear(projectId); }
        catch(RuntimeException failure) {
            try { lifecycle.finishClear(projectId,generation); }
            catch(RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        lifecycle.finishClear(projectId,generation);
    }
    public Entity entity(UUID projectId, UUID entityId, AuthenticatedActor actor) {
        projects.requireAccess(projectId, actor);
        return requireEntity(projectId,entityId,actor);
    }
    public Entity put(UUID projectId, AuthenticatedActor actor, EntityRequest request) {
        var project = projects.getProject(projectId, actor);
        String external = request.externalId();
        String name = request.displayName().trim();
        if (external.isBlank() || name.isBlank()) throw invalid();
        UUID id;
        Source source = request.source();
        if (request.type() == EntityType.PROJECT) {
            if (!projectId.toString().equals(external) || source != null) throw invalid();
            id = projectId; name = project.name();
        } else {
            var document = source(projectId, actor, source);
            if (request.type() == EntityType.WIKI || request.type() == EntityType.TASK) {
                if (!request.type().name().equals(source.type().name()) || !external.equals(source.id().toString())) throw invalid();
                name = document.title();
            }
            id = stable(projectId + ":entity:" + request.type() + ":" + external);
        }
        return store.put(new Entity(id, projectId, request.type(), external, name, source, 0), request.expectedVersion());
    }
    public Page<Entity> entities(UUID projectId, AuthenticatedActor actor, String after, int limit) {
        projects.requireAccess(projectId, actor);
        checkPage(after, limit);
        var page = store.entities(projectId, after, limit);
        return new Page<>(page.items().stream().map(e -> current(e, actor)).filter(java.util.Objects::nonNull).toList(), page.nextAfter());
    }
    public Relation putRelation(UUID projectId, AuthenticatedActor actor, RelationRequest request) {
        projects.requireAccess(projectId, actor);
        var from = requireEntity(projectId, request.fromId(), actor);
        var to = requireEntity(projectId, request.toId(), actor);
        if (!request.type().allows(from.type(), to.type())) throw invalid();
        var input = request.evidence();
        var document = source(projectId, actor, input.source());
        if (!Double.isFinite(input.confidence()) || input.confidence() < 0 || input.confidence() > 1
            || input.start() < 0 || input.end() <= input.start() || input.end() > document.text().length()
            || !document.text().substring(input.start(), input.end()).equals(input.excerpt())) throw invalid();
        UUID id = stable(projectId + ":relation:" + request.type() + ":" + from.id() + ":" + to.id());
        UUID evidenceId = stable(id + ":evidence:" + input.source().type() + ":" + input.source().id()
            + ":" + input.source().version() + ":" + input.chunkIndex() + ":" + input.start() + ":" + input.end());
        var evidence = new Evidence(evidenceId, projectId, input.source(), input.chunkIndex(),
            input.start(), input.end(), input.excerpt(), input.confidence(), 0);
        var result = store.putRelation(new Relation(id, projectId, request.type(), from.id(), to.id(),
            java.util.List.of(evidence), false), input.expectedVersion());
        var current = visible(result, actor);
        if (current == null) throw new ConflictException("The graph source changed during write.");
        return current;
    }
    public Page<Relation> neighbors(UUID projectId, UUID entityId, AuthenticatedActor actor, String after, int limit) {
        projects.requireAccess(projectId, actor);
        checkPage(after, limit);
        requireEntity(projectId, entityId, actor);
        var page = store.neighbors(projectId, entityId, after, limit);
        return new Page<>(page.items().stream().map(r -> visible(r, actor)).filter(java.util.Objects::nonNull).toList(), page.nextAfter());
    }
    private Entity requireEntity(UUID projectId, UUID id, AuthenticatedActor actor) {
        var e = store.entity(projectId, id).orElseThrow(() -> new ResourceNotFoundException("Graph entity not found."));
        var current = current(e, actor);
        if (current == null) throw new ResourceNotFoundException("Graph entity not found.");
        return current;
    }
    private Relation visible(Relation relation, AuthenticatedActor actor) {
        try {
            requireEntity(relation.projectId(), relation.fromId(), actor);
            requireEntity(relation.projectId(), relation.toId(), actor);
        } catch (ResourceNotFoundException expired) { return null; }
        var evidence = relation.evidence().stream().filter(e -> {
            try {
                var document = source(relation.projectId(), actor, e.source());
                return e.start() >= 0 && e.end() > e.start() && e.end() <= document.text().length()
                    && document.text().substring(e.start(), e.end()).equals(e.excerpt());
            } catch (ResourceNotFoundException | ConflictException expired) { return false; }
        }).toList();
        return evidence.isEmpty() ? null : new Relation(relation.id(), relation.projectId(), relation.type(),
            relation.fromId(), relation.toId(), evidence, relation.hasMoreEvidence());
    }
    private Entity current(Entity entity, AuthenticatedActor actor) {
        if (entity.type() == EntityType.PROJECT) {
            return new Entity(entity.id(), entity.projectId(), entity.type(), entity.externalId(),
                projects.getProject(entity.projectId(), actor).name(), null, entity.version());
        }
        try {
            var document = source(entity.projectId(), actor, entity.source());
            String name = entity.type() == EntityType.WIKI || entity.type() == EntityType.TASK ? document.title() : entity.displayName();
            return new Entity(entity.id(), entity.projectId(), entity.type(), entity.externalId(), name, entity.source(), entity.version());
        } catch (ResourceNotFoundException | ConflictException expired) { return null; }
    }
    private record Document(String title, String text) {}
    private Document source(UUID projectId, AuthenticatedActor actor, Source source) {
        if (source == null || source.type() == null || source.id() == null || source.version() == null || source.version() < 0) throw invalid();
        if (source.type() == SourceType.WIKI) {
            var page = wiki.get(projectId, source.id(), actor);
            if (page.version() != source.version()) throw new ConflictException("The graph source version is stale.");
            return new Document(page.title(), page.content());
        }
        var task = tasks.get(projectId, source.id(), actor);
        if (task.version() != source.version()) throw new ConflictException("The graph source version is stale.");
        return new Document(task.title(), task.description() == null ? "" : task.description());
    }
    private static UUID stable(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }
    private static void checkPage(String after, int limit) {
        if (limit < 1 || limit > 100 || after == null) throw invalid();
        if (!after.isEmpty()) try { if (!UUID.fromString(after).toString().equals(after)) throw invalid(); }
            catch (IllegalArgumentException e) { throw invalid(); }
    }
    private static ConstraintViolationException invalid() { return new ConstraintViolationException("Invalid graph input.", Set.of()); }
}
