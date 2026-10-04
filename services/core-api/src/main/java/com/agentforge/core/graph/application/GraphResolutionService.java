package com.agentforge.core.graph.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.agentforge.core.agent.application.AiUsageQuota;
import com.agentforge.core.graph.domain.GraphModel.*;
import com.agentforge.core.graph.domain.GraphStore;
import com.agentforge.core.security.AuthenticatedActor;

@Service
public class GraphResolutionService {
    private final GraphService graph;
    private final GraphStore store;
    private final GraphResolutionAdvisor advisor;
    private final GraphResolutionDecisionService decisions;
    private final AiUsageQuota aiUsageQuota;
    public GraphResolutionService(GraphService graph, GraphStore store,
        GraphResolutionAdvisor advisor, GraphResolutionDecisionService decisions,
        AiUsageQuota aiUsageQuota) {
        this.graph=graph; this.store=store; this.advisor=advisor; this.decisions=decisions;
        this.aiUsageQuota=aiUsageQuota;
    }
    public record Candidate(UUID entityId, String displayName, double ruleScore) {}
    public record Suggestion(UUID entityId, List<Candidate> candidates,
        UUID recommendedCandidateId, double confidence, boolean reviewRequired, boolean truncated) {}

    public Suggestion suggest(UUID projectId, UUID entityId, AuthenticatedActor actor) {
        var source=graph.entity(projectId,entityId,actor);
        if (!resolvable(source.type())) throw new IllegalArgumentException("Entity type cannot be resolved.");
        var candidates=new ArrayList<Candidate>();
        String normalized=normalize(source.displayName());
        String after="";
        int scanned=0;
        boolean truncated=false;
        while(scanned<500) {
            var page=store.entities(projectId,after,100);
            for(var raw:page.items()) {
                scanned++;
                if(raw.id().equals(entityId) || raw.type()!=source.type()) continue;
                Entity current;
                try { current=graph.entity(projectId,raw.id(),actor); }
                catch (com.agentforge.core.shared.error.ResourceNotFoundException expired) { continue; }
                double score=similarity(normalized,normalize(current.displayName()));
                var decision=decisions.decision(projectId,current.id(),actor);
                if(decision.status().equals("CONFIRMED"))
                    for(String alias:decision.aliases()) score=Math.max(score,similarity(normalized,normalize(alias)));
                if(score>0) candidates.add(new Candidate(current.id(),current.displayName(),score));
            }
            if(page.nextAfter()==null) break;
            after=page.nextAfter();
            if(scanned>=500) truncated=true;
        }
        candidates.sort(Comparator.comparingDouble(Candidate::ruleScore).reversed()
            .thenComparing(c -> c.entityId().toString()));
        if(candidates.size()>20) {
            truncated=true;
            candidates.subList(20,candidates.size()).clear();
        }
        var advice=new GraphResolutionAdvisor.Advice(null,0);
        if(!candidates.isEmpty()) {
            aiUsageQuota.consume(actor.userId());
            advice=advisor.suggest(source.id(),source.displayName(),source.type().name(),
                candidates.stream().map(c -> new GraphResolutionAdvisor.Candidate(c.entityId(),c.displayName())).toList());
        }
        return new Suggestion(entityId,List.copyOf(candidates),advice.recommendedCandidateId(),
            advice.confidence(),true,truncated);
    }
    private static boolean resolvable(EntityType type) {
        return type==EntityType.SERVICE || type==EntityType.API || type==EntityType.ISSUE;
    }
    private static String normalize(String name) {
        return Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
    }
    private static double similarity(String left,String right) {
        if(left.equals(right)) return 1;
        var a=new java.util.HashSet<>(List.of(left.split(" ")));
        var b=new java.util.HashSet<>(List.of(right.split(" ")));
        if(a.isEmpty() || b.isEmpty()) return 0;
        var intersection=new java.util.HashSet<>(a); intersection.retainAll(b);
        var union=new java.util.HashSet<>(a); union.addAll(b);
        return (double)intersection.size()/union.size();
    }
}
