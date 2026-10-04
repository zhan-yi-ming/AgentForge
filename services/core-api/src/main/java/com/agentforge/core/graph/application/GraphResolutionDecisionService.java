package com.agentforge.core.graph.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import jakarta.validation.ConstraintViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentforge.core.graph.domain.GraphModel.*;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;

@Service
public class GraphResolutionDecisionService {
    private static final Set<String> METADATA_KEYS=Set.of("team","domain","environment");
    private final GraphService graph;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public GraphResolutionDecisionService(GraphService graph, JdbcTemplate jdbc, ObjectMapper json) {
        this.graph=graph; this.jdbc=jdbc; this.json=json;
    }
    public record ConfirmRequest(UUID canonicalEntityId, String canonicalName, List<String> aliases,
        Map<String,String> metadata, Double confidence, Long sourceVersion, Long canonicalSourceVersion, Long expectedVersion) {}
    public record Decision(UUID entityId, UUID canonicalEntityId, String canonicalName,
        List<String> aliases, Map<String,String> metadata, Double confidence, String status, long version) {}
    private record Canonical(UUID id, EntityType type, String name, Map<String,String> metadata,
        SourceType sourceType, UUID sourceId, long sourceVersion, long version) {}
    public record CanonicalView(UUID canonicalEntityId, String canonicalName, Map<String,String> metadata,
        long sourceVersion, long version) {}
    public record UpdateCanonicalRequest(String canonicalName, Map<String,String> metadata,
        Long sourceVersion, Long expectedVersion) {}
    private record Member(UUID canonicalId, SourceType sourceType, UUID sourceId,
        long sourceVersion, long canonicalSourceVersion, List<String> aliases, double confidence, String status, long version) {}

    @Transactional
    public Decision confirm(UUID projectId, UUID entityId, AuthenticatedActor actor, ConfirmRequest request) {
        var member=graph.entity(projectId,entityId,actor);
        if (request == null || request.canonicalEntityId()==null || request.expectedVersion()==null
            || request.expectedVersion()<0 || request.canonicalName()==null || request.confidence()==null
            || !Double.isFinite(request.confidence()) || request.confidence()<0 || request.confidence()>1
            || !resolvable(member.type()) || member.source()==null
            || request.sourceVersion()==null || request.canonicalSourceVersion()==null
            || request.sourceVersion()<0 || request.canonicalSourceVersion()<0) throw invalid();
        lockRoles(projectId,entityId,request.canonicalEntityId());
        var anchor=graph.entity(projectId,request.canonicalEntityId(),actor);
        if (anchor.type()!=member.type() || anchor.source()==null) throw invalid();
        if(entityId.equals(anchor.id())) throw invalid();
        if(canonical(projectId,entityId).isPresent())
            throw new ConflictException("A canonical anchor cannot become a member.");
        var anchorMember=member(projectId,anchor.id());
        if(anchorMember.isPresent() && anchorMember.get().status().equals("CONFIRMED"))
            throw new ConflictException("A mapped member cannot be a canonical anchor.");
        if (!request.sourceVersion().equals(member.source().version())
            || !request.canonicalSourceVersion().equals(anchor.source().version()))
            throw new ConflictException("The graph source changed since the suggestion.");
        String name=request.canonicalName().trim();
        if(name.isEmpty() || name.length()>200 || request.aliases()==null || request.aliases().size()>10
            || request.metadata()==null || request.metadata().size()>10) throw invalid();
        var aliases=new java.util.ArrayList<String>();
        var unique=new LinkedHashSet<String>();
        for(String alias:request.aliases()) {
            if(alias==null || alias.isBlank() || alias.length()>200) throw invalid();
            String trimmed=alias.trim();
            if(!unique.add(trimmed.toLowerCase(java.util.Locale.ROOT))) throw invalid();
            aliases.add(trimmed);
        }
        var metadata=new java.util.LinkedHashMap<String,String>();
        for(var entry:request.metadata().entrySet()) {
            if(!METADATA_KEYS.contains(entry.getKey()) || entry.getValue()==null
                || entry.getValue().isBlank() || entry.getValue().length()>200) throw invalid();
            metadata.put(entry.getKey(),entry.getValue().trim());
        }
        String metadataJson=encode(metadata);
        String aliasesJson=encode(aliases);
        jdbc.update("""
            INSERT INTO graph_canonical_entity
                (project_id,id,entity_type,canonical_name,metadata,anchor_source_type,anchor_source_id,anchor_source_version)
            VALUES (?,?,?,?,?::jsonb,?,?,?) ON CONFLICT DO NOTHING
            """,projectId,anchor.id(),anchor.type().name(),name,metadataJson,
            anchor.source().type().name(),anchor.source().id(),anchor.source().version());
        var canonical=canonical(projectId,anchor.id()).orElseThrow();
        if(canonical.type()!=anchor.type() || !canonical.name().equals(name)
            || !canonical.metadata().equals(metadata)
            || canonical.sourceType()!=anchor.source().type()
            || !canonical.sourceId().equals(anchor.source().id()))
            throw new ConflictException("The canonical entity changed.");
        if(canonical.sourceVersion()!=anchor.source().version()) {
            if(canonical.sourceVersion()>anchor.source().version())
                throw new ConflictException("The canonical source version moved backward.");
            int refreshed=jdbc.update("""
                UPDATE graph_canonical_entity SET anchor_source_version=?,version=version+1,updated_at=now()
                WHERE project_id=? AND id=? AND anchor_source_version=? AND version=?
                """,anchor.source().version(),projectId,anchor.id(),canonical.sourceVersion(),canonical.version());
            if(refreshed!=1) throw new ConflictException("The canonical entity changed.");
            canonicalEvent(projectId,anchor.id(),"REFRESH_SOURCE",canonical.name(),canonical.name(),
                canonical.metadata(),canonical.metadata(),canonical.sourceVersion(),anchor.source().version(),
                actor.userId(),canonical.version()+1);
        }
        var old=member(projectId,entityId);
        long version;
        UUID previous=null;
        if(old.isEmpty()) {
            if(request.expectedVersion()!=0) throw new ConflictException("The resolution version is stale.");
            int inserted=jdbc.update("""
                INSERT INTO graph_resolution_member
                    (project_id,entity_id,canonical_id,source_type,source_id,source_version,canonical_source_version,aliases,confidence,status,version,confirmed_by)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,'CONFIRMED',1,?) ON CONFLICT DO NOTHING
                """,projectId,entityId,anchor.id(),member.source().type().name(),member.source().id(),
                member.source().version(),anchor.source().version(),aliasesJson,request.confidence(),actor.userId());
            if(inserted!=1) throw new ConflictException("The resolution version is stale.");
            version=1;
        } else {
            var current=old.get();
            if(current.status().equals("CONFIRMED") && current.canonicalId().equals(anchor.id())
                && current.sourceType()==member.source().type() && current.sourceId().equals(member.source().id())
                && current.sourceVersion()==member.source().version()
                && current.canonicalSourceVersion()==anchor.source().version()
                && current.aliases().equals(aliases)
                && Double.compare(current.confidence(),request.confidence())==0)
                return new Decision(entityId,anchor.id(),name,List.copyOf(aliases),Map.copyOf(metadata),
                    request.confidence(),"CONFIRMED",current.version());
            if(current.version()!=request.expectedVersion()) throw new ConflictException("The resolution version is stale.");
            previous=current.canonicalId();
            int updated=jdbc.update("""
                UPDATE graph_resolution_member SET canonical_id=?,source_type=?,source_id=?,source_version=?,canonical_source_version=?,
                    aliases=?::jsonb,confidence=?,status='CONFIRMED',version=version+1,confirmed_by=?,updated_at=now()
                WHERE project_id=? AND entity_id=? AND version=?
                """,anchor.id(),member.source().type().name(),member.source().id(),member.source().version(),
                anchor.source().version(),aliasesJson,request.confidence(),actor.userId(),projectId,entityId,current.version());
            if(updated!=1) throw new ConflictException("The resolution version is stale.");
            version=current.version()+1;
        }
        event(projectId,entityId,"CONFIRM",previous,anchor.id(),actor.userId(),version);
        return new Decision(entityId,anchor.id(),name,List.copyOf(aliases),Map.copyOf(metadata),
            request.confidence(),"CONFIRMED",version);
    }

    public CanonicalView canonicalView(UUID projectId, UUID canonicalId, AuthenticatedActor actor) {
        var anchor=graph.entity(projectId,canonicalId,actor);
        if(!resolvable(anchor.type()) || anchor.source()==null) throw invalid();
        var stored=canonical(projectId,canonicalId).orElseThrow(
            () -> new ResourceNotFoundException("Canonical entity not found."));
        if(stored.type()!=anchor.type() || stored.sourceType()!=anchor.source().type()
            || !stored.sourceId().equals(anchor.source().id())
            || stored.sourceVersion()!=anchor.source().version())
            throw new ResourceNotFoundException("Canonical entity not found.");
        return new CanonicalView(canonicalId,stored.name(),stored.metadata(),
            stored.sourceVersion(),stored.version());
    }

    @Transactional
    public CanonicalView updateCanonical(UUID projectId, UUID canonicalId, AuthenticatedActor actor,
        UpdateCanonicalRequest request) {
        var current=canonicalView(projectId,canonicalId,actor);
        if(request==null || request.canonicalName()==null || request.metadata()==null
            || request.sourceVersion()==null || request.expectedVersion()==null
            || request.sourceVersion()<0 || request.expectedVersion()<1) throw invalid();
        String name=request.canonicalName().trim();
        if(name.isEmpty() || name.length()>200 || request.metadata().size()>10) throw invalid();
        var metadata=new java.util.LinkedHashMap<String,String>();
        for(var entry:request.metadata().entrySet()) {
            if(!METADATA_KEYS.contains(entry.getKey()) || entry.getValue()==null
                || entry.getValue().isBlank() || entry.getValue().length()>200) throw invalid();
            metadata.put(entry.getKey(),entry.getValue().trim());
        }
        if(current.sourceVersion()!=request.sourceVersion())
            throw new ConflictException("The graph source changed since the edit.");
        if(current.canonicalName().equals(name) && current.metadata().equals(metadata))
            return current;
        if(current.version()!=request.expectedVersion())
            throw new ConflictException("The canonical version is stale.");
        int changed=jdbc.update("""
            UPDATE graph_canonical_entity SET canonical_name=?,metadata=?::jsonb,
                version=version+1,updated_at=now()
            WHERE project_id=? AND id=? AND version=? AND anchor_source_version=?
            """,name,encode(metadata),projectId,canonicalId,current.version(),current.sourceVersion());
        if(changed!=1) throw new ConflictException("The canonical version is stale.");
        canonicalEvent(projectId,canonicalId,"UPDATE",current.canonicalName(),name,
            current.metadata(),metadata,current.sourceVersion(),current.sourceVersion(),
            actor.userId(),current.version()+1);
        return new CanonicalView(canonicalId,name,Map.copyOf(metadata),current.sourceVersion(),current.version()+1);
    }

    @Transactional
    public void revert(UUID projectId, UUID entityId, AuthenticatedActor actor, Long expectedVersion) {
        var entity=graph.entity(projectId,entityId,actor);
        if(!resolvable(entity.type()) || expectedVersion==null || expectedVersion<0) throw invalid();
        var stored=member(projectId,entityId).orElseThrow(
            () -> new ResourceNotFoundException("Resolution decision not found."));
        if(stored.status().equals("REVERTED")) {
            if(expectedVersion==stored.version() || expectedVersion==stored.version()-1) return;
            throw new ConflictException("The resolution version is stale.");
        }
        if(stored.version()!=expectedVersion) throw new ConflictException("The resolution version is stale.");
        int changed=jdbc.update("""
            UPDATE graph_resolution_member SET canonical_id=NULL,aliases='[]'::jsonb,
                confidence=0,status='REVERTED',version=version+1,confirmed_by=?,updated_at=now()
            WHERE project_id=? AND entity_id=? AND version=? AND status='CONFIRMED'
            """,actor.userId(),projectId,entityId,stored.version());
        if(changed!=1) throw new ConflictException("The resolution version is stale.");
        event(projectId,entityId,"REVERT",stored.canonicalId(),null,actor.userId(),stored.version()+1);
    }
    public Decision decision(UUID projectId, UUID entityId, AuthenticatedActor actor) {
        var entity=graph.entity(projectId,entityId,actor);
        if(!resolvable(entity.type())) throw invalid();
        var stored=member(projectId,entityId);
        if(stored.isEmpty()) return unmapped(entityId,0);
        var member=stored.get();
        if(!member.status().equals("CONFIRMED") || member.sourceType()!=entity.source().type()
            || !member.sourceId().equals(entity.source().id())
            || member.sourceVersion()!=entity.source().version()) return unmapped(entityId,member.version());
        var canonical=canonical(projectId,member.canonicalId());
        if(canonical.isEmpty()) return unmapped(entityId,member.version());
        Entity anchor;
        try { anchor=graph.entity(projectId,member.canonicalId(),actor); }
        catch(ResourceNotFoundException expired) { return unmapped(entityId,member.version()); }
        var target=canonical.get();
        if(anchor.type()!=entity.type() || anchor.source()==null
            || target.sourceType()!=anchor.source().type()
            || !target.sourceId().equals(anchor.source().id())
            || target.sourceVersion()!=anchor.source().version()
            || member.canonicalSourceVersion()!=target.sourceVersion()) return unmapped(entityId,member.version());
        return new Decision(entityId,anchor.id(),target.name(),member.aliases(),target.metadata(),
            member.confidence(),"CONFIRMED",member.version());
    }

    private java.util.Optional<Canonical> canonical(UUID projectId, UUID id) {
        return jdbc.query("""
            SELECT id,entity_type,canonical_name,metadata::text AS metadata,
                anchor_source_type,anchor_source_id,anchor_source_version,version
            FROM graph_canonical_entity WHERE project_id=? AND id=?
            """,(rs,i)->new Canonical(rs.getObject("id",UUID.class),EntityType.valueOf(rs.getString("entity_type")),
                rs.getString("canonical_name"),decodeMap(rs.getString("metadata")),
                SourceType.valueOf(rs.getString("anchor_source_type")),
                rs.getObject("anchor_source_id",UUID.class),rs.getLong("anchor_source_version"),
                rs.getLong("version")),projectId,id)
            .stream().findFirst();
    }
    private void lockRoles(UUID projectId, UUID memberId, UUID canonicalId) {
        java.util.stream.Stream.of(memberId,canonicalId)
            .map(UUID::toString)
            .sorted()
            .forEach(entityId -> jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))::text",
                String.class,projectId+":resolution-role:"+entityId));
    }
    private java.util.Optional<Member> member(UUID projectId, UUID id) {
        return jdbc.query("""
            SELECT canonical_id,source_type,source_id,source_version,canonical_source_version,aliases::text AS aliases,
                confidence,status,version FROM graph_resolution_member WHERE project_id=? AND entity_id=?
            """,(rs,i)->new Member(rs.getObject("canonical_id",UUID.class),
                SourceType.valueOf(rs.getString("source_type")),rs.getObject("source_id",UUID.class),
                rs.getLong("source_version"),rs.getLong("canonical_source_version"),decodeList(rs.getString("aliases")),
                rs.getDouble("confidence"),rs.getString("status"),rs.getLong("version")),projectId,id)
            .stream().findFirst();
    }
    private void canonicalEvent(UUID projectId, UUID canonicalId, String action,
        String previousName, String name, Map<String,String> previousMetadata,
        Map<String,String> metadata, long previousSourceVersion, long sourceVersion,
        UUID actor, long version) {
        jdbc.update("""
            INSERT INTO graph_canonical_event
                (id,project_id,canonical_id,action,previous_source_version,source_version,
                    previous_name,canonical_name,previous_metadata,metadata,actor_user_id,canonical_version)
            VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)
            """,UUID.randomUUID(),projectId,canonicalId,action,previousSourceVersion,sourceVersion,
            previousName,name,encode(previousMetadata),encode(metadata),actor,version);
    }

    private void event(UUID projectId,UUID entityId,String action,UUID previous,UUID canonical,
        UUID actor,long version) {
        jdbc.update("""
            INSERT INTO graph_resolution_event
                (id,project_id,entity_id,action,previous_canonical_id,canonical_id,actor_user_id,member_version)
            VALUES (?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),projectId,entityId,action,previous,canonical,actor,version);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("Resolution JSON encoding failed.",failure); }
    }
    private Map<String,String> decodeMap(String value) {
        try { return json.readValue(value,new TypeReference<>() {}); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("Resolution metadata is invalid.",failure); }
    }
    private List<String> decodeList(String value) {
        try { return json.readValue(value,new TypeReference<>() {}); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("Resolution aliases are invalid.",failure); }
    }
    private static Decision unmapped(UUID entityId,long version) {
        return new Decision(entityId,null,null,List.of(),Map.of(),null,"UNMAPPED",version);
    }
    private static boolean resolvable(EntityType type) {
        return type==EntityType.SERVICE || type==EntityType.API || type==EntityType.ISSUE;
    }
    private static ConstraintViolationException invalid() {
        return new ConstraintViolationException("Invalid resolution input.",Set.of());
    }
}
