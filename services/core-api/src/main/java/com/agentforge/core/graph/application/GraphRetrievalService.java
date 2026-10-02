package com.agentforge.core.graph.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.agentforge.core.graph.domain.GraphModel.*;
import com.agentforge.core.graph.domain.GraphStore;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.shared.error.UnauthorizedException;
import com.agentforge.core.user.UserDirectory;

@Service
public class GraphRetrievalService {
    private static final int MAX_SCANNED = 500;
    private static final int MAX_ROOTS = 4;
    private static final int MAX_NEIGHBORS = 12;
    private static final int MAX_RELATIONS = 40;
    private static final int MAX_MATCHES = 40;
    private final UserDirectory users;
    private final ProjectAccess projects;
    private final GraphService graph;
    private final GraphStore store;
    private final GraphResolutionDecisionService decisions;

    public record EntityView(UUID entityId, EntityType entityType, String displayName, UUID canonicalEntityId) {}
    public record EvidenceView(SourceType sourceType, UUID sourceId, long sourceVersion,
        String excerpt, double confidence) {}
    public record Match(int hop, UUID relationId, RelationType relationType,
        EntityView from, EntityView to, EvidenceView evidence) {}
    private record Root(Entity entity, int score) {}
    private record Frontier(UUID id, int hop) {}

    public GraphRetrievalService(UserDirectory users, ProjectAccess projects, GraphService graph,
        GraphStore store, GraphResolutionDecisionService decisions) {
        this.users=users; this.projects=projects; this.graph=graph; this.store=store; this.decisions=decisions;
    }

    public List<Match> retrieve(UUID projectId, UUID userId, boolean actorAdmin, String query) {
        try { users.requireUserExists(userId); }
        catch (ResourceNotFoundException invalid) { throw new UnauthorizedException("The internal actor is not valid."); }
        var actor=new AuthenticatedActor(userId,actorAdmin);
        projects.requireAccess(projectId,actor);
        String normalized=normalize(query);
        if(normalized.isEmpty()) return List.of();
        var roots=new ArrayList<Root>();
        String after="";
        int scanned=0;
        while(scanned<MAX_SCANNED) {
            var page=store.entities(projectId,after,Math.min(100,MAX_SCANNED-scanned));
            scanned+=page.items().size();
            for(var candidate:page.items()) {
                Entity entity;
                try { entity=graph.entity(projectId,candidate.id(),actor); }
                catch(ResourceNotFoundException expired) { continue; }
                try {
                    int score=score(normalized,entity.displayName());
                    if(resolvable(entity.type())) {
                        var decision=decisions.decision(projectId,entity.id(),actor);
                        if("CONFIRMED".equals(decision.status())) {
                            score=Math.max(score,score(normalized,decision.canonicalName()));
                            for(String alias:decision.aliases()) score=Math.max(score,score(normalized,alias));
                        }
                    }
                    if(score>0) roots.add(new Root(entity,score));
                } catch(ResourceNotFoundException expiredDuringResolution) {
                    // The source changed between candidate validation and resolution lookup.
                }
            }
            if(page.nextAfter()==null || page.items().isEmpty()) break;
            after=page.nextAfter();
        }
        roots.sort(Comparator.comparingInt(Root::score).reversed()
            .thenComparing(root -> root.entity().id().toString()));
        var frontier=new ArrayList<Frontier>();
        var visitedNodes=new HashSet<UUID>();
        for(var root:roots.stream().limit(MAX_ROOTS).toList()) {
            frontier.add(new Frontier(root.entity().id(),1));
            visitedNodes.add(root.entity().id());
        }
        var matches=new LinkedHashMap<UUID,Match>();
        int visitedRelations=0;
        for(int index=0; index<frontier.size() && visitedRelations<MAX_RELATIONS && matches.size()<MAX_MATCHES; index++) {
            var node=frontier.get(index);
            Page<Relation> page;
            try { page=graph.neighbors(projectId,node.id(),actor,"",MAX_NEIGHBORS); }
            catch(ResourceNotFoundException expiredDuringTraversal) { continue; }
            for(var relation:page.items()) {
                if(visitedRelations++>=MAX_RELATIONS || matches.size()>=MAX_MATCHES) break;
                if(matches.containsKey(relation.id())) continue;
                Entity from;
                Entity to;
                try {
                    from=graph.entity(projectId,relation.fromId(),actor);
                    to=graph.entity(projectId,relation.toId(),actor);
                } catch(ResourceNotFoundException expired) { continue; }
                if(relation.evidence().isEmpty()) continue;
                try {
                    var evidence=relation.evidence().getFirst();
                    matches.put(relation.id(),new Match(node.hop(),relation.id(),relation.type(),
                        view(projectId,from,actor),view(projectId,to,actor),
                        new EvidenceView(evidence.source().type(),evidence.source().id(),evidence.source().version(),
                            evidence.excerpt(),evidence.confidence())));
                } catch(ResourceNotFoundException expiredDuringResolution) { continue; }
                if(node.hop()<2) {
                    UUID next=relation.fromId().equals(node.id()) ? relation.toId() : relation.fromId();
                    if(visitedNodes.add(next) && frontier.size()<MAX_RELATIONS) frontier.add(new Frontier(next,2));
                }
            }
        }
        return List.copyOf(matches.values());
    }
    private EntityView view(UUID projectId, Entity entity, AuthenticatedActor actor) {
        if(!resolvable(entity.type())) return new EntityView(entity.id(),entity.type(),entity.displayName(),null);
        var decision=decisions.decision(projectId,entity.id(),actor);
        if("CONFIRMED".equals(decision.status()))
            return new EntityView(entity.id(),entity.type(),decision.canonicalName(),decision.canonicalEntityId());
        return new EntityView(entity.id(),entity.type(),entity.displayName(),null);
    }
    private static boolean resolvable(EntityType type) {
        return type==EntityType.SERVICE || type==EntityType.API || type==EntityType.ISSUE;
    }
    private static String normalize(String input) {
        return Normalizer.normalize(input,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
    }
    private static int score(String query,String name) {
        String normalized=normalize(name);
        if(normalized.isEmpty()) return 0;
        if(query.contains(normalized)) return 3;
        int common=0;
        Set<String> terms=new HashSet<>(List.of(normalized.split(" ")));
        for(String term:query.split(" ")) if(term.length()>=3 && terms.contains(term)) common++;
        return common>0 ? 1+common : 0;
    }
}
