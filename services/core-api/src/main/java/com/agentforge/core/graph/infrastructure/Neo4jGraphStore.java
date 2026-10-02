package com.agentforge.core.graph.infrastructure;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.agentforge.core.graph.domain.GraphModel.*;
import com.agentforge.core.graph.domain.GraphStore;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ServiceUnavailableException;
@Component
public class Neo4jGraphStore implements GraphStore {
    private final boolean enabled;
    private final String uri, username, password;
    private volatile Driver driver;
    private volatile boolean initialized;
    private static final TransactionConfig TX = TransactionConfig.builder().withTimeout(Duration.ofSeconds(5)).build();
    public Neo4jGraphStore(@Value("${agentforge.graph.enabled:false}") boolean enabled,
        @Value("${agentforge.graph.uri:bolt://localhost:7687}") String uri,
        @Value("${agentforge.graph.username:neo4j}") String username,
        @Value("${agentforge.graph.password:}") String password) {
        this.enabled=enabled; this.uri=uri; this.username=username; this.password=password;
    }
    private synchronized Driver driver() {
        if (!enabled) throw new ServiceUnavailableException("Graph service is unavailable.");
        if (driver == null) driver = GraphDatabase.driver(uri, AuthTokens.basic(username,password),
            Config.builder().withConnectionTimeout(2,TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(3,TimeUnit.SECONDS).withMaxTransactionRetryTime(2,TimeUnit.SECONDS).build());
        if (!initialized) {
            try(var session=driver.session()) {
                session.run("CREATE CONSTRAINT graph_relation_id_v1 IF NOT EXISTS FOR (n:GraphRelation) REQUIRE n.id IS UNIQUE", Map.of(), TX).consume();
                session.run("CREATE CONSTRAINT graph_evidence_id_v1 IF NOT EXISTS FOR (n:GraphEvidence) REQUIRE n.id IS UNIQUE", Map.of(), TX).consume();
                session.run("CREATE CONSTRAINT graph_entity_id_v1 IF NOT EXISTS FOR (n:GraphEntity) REQUIRE n.id IS UNIQUE", Map.of(), TX).consume();
                session.run("CREATE CONSTRAINT graph_project_lock_id_v1 IF NOT EXISTS FOR (n:GraphProjectLock) REQUIRE n.id IS UNIQUE", Map.of(), TX).consume();
            }
            initialized=true;
        }
        return driver;
    }
    private <T> T transaction(boolean write, Function<TransactionContext,T> work) {
        try(var session=driver().session()) {
            return write ? session.executeWrite(work::apply, TX) : session.executeRead(work::apply, TX);
        } catch (Neo4jException | IllegalArgumentException e) {
            throw new ServiceUnavailableException("Graph service is unavailable.");
        }
    }
    private void lock(TransactionContext tx, UUID project) {
        tx.run("MERGE (p:GraphProjectLock {id:$project}) SET p.revision=coalesce(p.revision,0)+1",
            Map.of("project",project.toString())).consume();
    }
    @Override public void clear(UUID projectId) {
        transaction(true,tx -> {
            lock(tx,projectId);
            tx.run("MATCH (n) WHERE n.projectId=$project AND (n:GraphEntity OR n:GraphRelation OR n:GraphEvidence) DETACH DELETE n",
                Map.of("project",projectId.toString())).consume();
            return null;
        });
    }
    @Override public Entity put(Entity e, long expectedVersion) {
        return transaction(true, tx -> {
            lock(tx,e.projectId());
            var existing=tx.run("MATCH (n:GraphEntity {id:$id,projectId:$project}) RETURN n",Map.of("id",e.id().toString(),"project",e.projectId().toString()));
            long version=1;
            if(existing.hasNext()) {
                var old=entity(existing.single().get("n"));
                if(old.displayName().equals(e.displayName()) && java.util.Objects.equals(old.source(),e.source())) return old;
                if(old.version()!=expectedVersion) throw new ConflictException("The graph entity version is stale.");
                version=old.version()+1;
            } else if(expectedVersion!=0) throw new ConflictException("The graph entity version is stale.");
            tx.run("MERGE (n:GraphEntity {id:$id}) SET n.projectId=$project,n.type=$type,n.externalId=$external,n.displayName=$name,n.version=$version,n.sourceType=$sourceType,n.sourceId=$sourceId,n.sourceVersion=$sourceVersion",
                entityParameters(e,version)).consume();
            return new Entity(e.id(),e.projectId(),e.type(),e.externalId(),e.displayName(),e.source(),version);
        });
    }
    @Override public Page<Entity> entities(UUID projectId,String after,int limit) {
        return transaction(false,tx -> {
            var rows=tx.run("MATCH (n:GraphEntity {projectId:$project}) WHERE n.id>$after RETURN n ORDER BY n.id LIMIT $limit",
                Map.of("project",projectId.toString(),"after",after,"limit",limit)).list(r -> entity(r.get("n")));
            return new Page<>(rows,rows.size()==limit ? rows.getLast().id().toString() : null);
        });
    }
    @Override public java.util.Optional<Entity> entity(UUID projectId,UUID id) {
        return transaction(false, tx -> entity(tx,projectId,id));
    }
    private java.util.Optional<Entity> entity(TransactionContext tx,UUID projectId,UUID id) {
        var result=tx.run("MATCH (n:GraphEntity {id:$id,projectId:$project}) RETURN n",
            Map.of("id",id.toString(),"project",projectId.toString()));
        return result.hasNext() ? java.util.Optional.of(entity(result.single().get("n"))) : java.util.Optional.empty();
    }
    @Override public Relation putRelation(Relation relation,long expectedVersion) {
        return transaction(true, tx -> {
            lock(tx,relation.projectId());
            var from=entity(tx,relation.projectId(),relation.fromId()).orElseThrow(() -> new com.agentforge.core.shared.error.ResourceNotFoundException("Graph entity not found."));
            var to=entity(tx,relation.projectId(),relation.toId()).orElseThrow(() -> new com.agentforge.core.shared.error.ResourceNotFoundException("Graph entity not found."));
            if(!relation.type().allows(from.type(),to.type())) throw new ConflictException("Invalid graph relation endpoints.");
            var e=relation.evidence().getFirst();
            var parameters=new java.util.HashMap<String,Object>();
            parameters.put("id",relation.id().toString()); parameters.put("project",relation.projectId().toString());
            parameters.put("type",relation.type().name()); parameters.put("from",relation.fromId().toString()); parameters.put("to",relation.toId().toString());
            parameters.put("evidence",e.id().toString()); parameters.put("sourceType",e.source().type().name()); parameters.put("sourceId",e.source().id().toString());
            parameters.put("sourceVersion",e.source().version()); parameters.put("chunk",e.chunkIndex()); parameters.put("start",e.start()); parameters.put("end",e.end());
            parameters.put("excerpt",e.excerpt()); parameters.put("confidence",e.confidence());
            var existing=tx.run("MATCH (e:GraphEvidence {id:$evidence,projectId:$project}) RETURN e",parameters);
            long version=1;
            boolean changed=true;
            if(existing.hasNext()) {
                var old=evidence(existing.single().get("e"));
                changed=Double.compare(old.confidence(),e.confidence())!=0 || !old.excerpt().equals(e.excerpt());
                if(changed && old.version()!=expectedVersion) throw new ConflictException("The graph evidence version is stale.");
                version=old.version()+(changed ? 1 : 0);
            } else if(expectedVersion!=0) throw new ConflictException("The graph evidence version is stale.");
            if(changed) {
                parameters.put("version",version);
                tx.run("""
                    MATCH (a:GraphEntity {id:$from,projectId:$project}), (b:GraphEntity {id:$to,projectId:$project})
                    MERGE (r:GraphRelation {id:$id}) SET r.projectId=$project,r.type=$type,r.fromId=$from,r.toId=$to
                    MERGE (r)-[:FROM]->(a) MERGE (r)-[:TO]->(b)
                    MERGE (e:GraphEvidence {id:$evidence}) SET e.projectId=$project,e.sourceType=$sourceType,e.sourceId=$sourceId,
                    e.sourceVersion=$sourceVersion,e.chunkIndex=$chunk,e.start=$start,e.end=$end,e.excerpt=$excerpt,e.confidence=$confidence,e.version=$version
                    MERGE (e)-[:SUPPORTS]->(r)
                    """,parameters).consume();
            }
            return relation(tx,relation.id(),relation.projectId()).orElseThrow(() -> new com.agentforge.core.shared.error.ResourceNotFoundException("Graph relation not found."));
        });
    }
    @Override public Page<Relation> neighbors(UUID projectId,UUID entityId,String after,int limit) {
        return transaction(false,tx -> {
            var ids=tx.run("MATCH (r:GraphRelation {projectId:$project}) WHERE (r.fromId=$entity OR r.toId=$entity) AND r.id>$after RETURN r.id AS id ORDER BY r.id LIMIT $limit",
                Map.of("project",projectId.toString(),"entity",entityId.toString(),"after",after,"limit",limit)).list(r -> UUID.fromString(r.get("id").asString()));
            var relations=ids.stream().flatMap(id -> relation(tx,id,projectId).stream()).toList();
            return new Page<>(relations,ids.size()==limit ? ids.getLast().toString() : null);
        });
    }
    private java.util.Optional<Relation> relation(TransactionContext tx,UUID id,UUID projectId) {
        var p=Map.<String,Object>of("id",id.toString(),"project",projectId.toString());
        var result=tx.run("MATCH (r:GraphRelation {id:$id,projectId:$project}) RETURN r",p);
        if (!result.hasNext()) return java.util.Optional.empty();
        var n=result.single().get("r").asNode();
        var evidence=tx.run("MATCH (e:GraphEvidence {projectId:$project})-[:SUPPORTS]->(r:GraphRelation {id:$id,projectId:$project}) RETURN e ORDER BY e.id LIMIT 21",p)
            .list(r -> evidence(r.get("e")));
        return java.util.Optional.of(new Relation(id,projectId,RelationType.valueOf(n.get("type").asString()),UUID.fromString(n.get("fromId").asString()),
            UUID.fromString(n.get("toId").asString()),evidence.stream().limit(20).toList(),evidence.size()>20));
    }
    private Evidence evidence(org.neo4j.driver.Value value) {
        var n=value.asNode();
        return new Evidence(UUID.fromString(n.get("id").asString()),UUID.fromString(n.get("projectId").asString()),
            new Source(SourceType.valueOf(n.get("sourceType").asString()),UUID.fromString(n.get("sourceId").asString()),n.get("sourceVersion").asLong()),
            n.get("chunkIndex").isNull() ? null : n.get("chunkIndex").asInt(),n.get("start").asInt(),n.get("end").asInt(),n.get("excerpt").asString(),n.get("confidence").asDouble(),n.get("version").asLong());
    }
    private Entity entity(org.neo4j.driver.Value v) {
        var n=v.asNode();
        return new Entity(UUID.fromString(n.get("id").asString()),UUID.fromString(n.get("projectId").asString()),
            EntityType.valueOf(n.get("type").asString()),n.get("externalId").asString(),n.get("displayName").asString(),n.get("sourceId").isNull() ? null : new Source(SourceType.valueOf(n.get("sourceType").asString()),UUID.fromString(n.get("sourceId").asString()),n.get("sourceVersion").asLong()),n.get("version").asLong());
    }
    private Map<String,Object> entityParameters(Entity e,long version) {
        var p=new java.util.HashMap<String,Object>();
        p.put("id",e.id().toString()); p.put("project",e.projectId().toString()); p.put("type",e.type().name());
        p.put("external",e.externalId()); p.put("name",e.displayName()); p.put("version",version);
        p.put("sourceType",e.source()==null ? null : e.source().type().name());
        p.put("sourceId",e.source()==null ? null : e.source().id().toString());
        p.put("sourceVersion",e.source()==null ? null : e.source().version());
        return p;
    }
    @Override public void replaceSource(UUID projectId, SourceType sourceType, UUID sourceId,
        com.agentforge.core.graph.domain.GraphExtraction.Projection projection) {
        transaction(true, tx -> {
            lock(tx,projectId);
            var base=Map.<String,Object>of("project",projectId.toString(),"type",sourceType.name(),"source",sourceId.toString());
            if (projection == null) {
                String sourceEntity=com.agentforge.core.graph.domain.GraphExtraction.stable(
                    projectId+":entity:"+sourceType+":"+sourceId).toString();
                var removed=Map.<String,Object>of("project",projectId.toString(),"sourceEntity",sourceEntity);
                tx.run("""
                    MATCH (e:GraphEvidence {projectId:$project,sourceType:$type,sourceId:$source})
                    DETACH DELETE e
                    """,base).consume();
                tx.run("""
                    MATCH (e:GraphEvidence)-[:SUPPORTS]->(r:GraphRelation {projectId:$project})
                    WHERE r.fromId=$sourceEntity OR r.toId=$sourceEntity
                    DETACH DELETE e
                    """,removed).consume();
                tx.run("""
                    MATCH (r:GraphRelation {projectId:$project})
                    WHERE r.fromId=$sourceEntity OR r.toId=$sourceEntity
                    DETACH DELETE r
                    """,removed).consume();
            }
            if (projection != null) {
                var current=new java.util.HashMap<String,Object>(base);
                current.put("version",projection.document().version());
                tx.run("""
                    MATCH (e:GraphEvidence {projectId:$project,sourceType:$type,sourceId:$source})
                    WHERE e.sourceVersion <> $version
                    DETACH DELETE e
                    """,current).consume();
            }
            tx.run("""
                MATCH (e:GraphEvidence {projectId:$project,origin:'EXTRACTED',sourceType:$type,sourceId:$source})
                DETACH DELETE e
                """,base).consume();
            tx.run("""
                MATCH (r:GraphRelation {projectId:$project})
                WHERE NOT EXISTS { MATCH (e:GraphEvidence)-[:SUPPORTS]->(r) }
                DETACH DELETE r
                """,base).consume();
            tx.run("""
                MATCH (n:GraphEntity {projectId:$project,origin:'EXTRACTED',sourceType:$type,sourceId:$source})
                WHERE n.type IN ['SERVICE','API','ISSUE']
                  AND NOT EXISTS {
                    MATCH (r:GraphRelation {projectId:$project})
                    WHERE (r.fromId=n.id OR r.toId=n.id)
                      AND EXISTS {
                        MATCH (e:GraphEvidence)-[:SUPPORTS]->(r)
                        WHERE coalesce(e.origin,'MANUAL') <> 'EXTRACTED'
                      }
                  }
                DETACH DELETE n
                """,base).consume();
            if (projection == null) {
                tx.run("""
                    MATCH (n:GraphEntity {projectId:$project})
                    WHERE n.id=$sourceEntity DETACH DELETE n
                    """,Map.of("project",projectId.toString(),"sourceEntity",
                        com.agentforge.core.graph.domain.GraphExtraction.stable(
                            projectId+":entity:"+sourceType+":"+sourceId).toString())).consume();
                return null;
            }
            var document=projection.document();
            var sourceEntity=new java.util.HashMap<String,Object>(base);
            sourceEntity.put("id",projection.sourceEntityId().toString());
            sourceEntity.put("external",sourceId.toString());
            sourceEntity.put("name",document.title());
            sourceEntity.put("version",document.version());
            tx.run("""
                MERGE (n:GraphEntity {id:$id})
                ON CREATE SET n.origin='EXTRACTED',n.version=1
                SET n.projectId=$project,n.type=$type,n.externalId=$external,n.displayName=$name,
                    n.sourceType=$type,n.sourceId=$source,n.sourceVersion=$version
                """,sourceEntity).consume();
            for (var target:projection.targets()) {
                var p=new java.util.HashMap<String,Object>(base);
                p.put("id",target.id().toString()); p.put("entityType",target.type().name());
                p.put("external",target.externalId()); p.put("name",target.name());
                p.put("version",document.version());
                tx.run("""
                    MERGE (n:GraphEntity {id:$id})
                    ON CREATE SET n.origin='EXTRACTED',n.version=1
                    SET n.projectId=$project,n.type=$entityType,n.externalId=$external,n.displayName=$name,
                        n.sourceType=$type,n.sourceId=$source,n.sourceVersion=$version
                    """,p).consume();
            }
            for (var claim:projection.claims()) {
                var e=claim.evidence();
                var p=new java.util.HashMap<String,Object>(base);
                p.put("id",claim.id().toString()); p.put("from",claim.fromId().toString());
                p.put("to",claim.toId().toString()); p.put("relationType",claim.type().name());
                p.put("evidence",e.id().toString()); p.put("version",document.version());
                p.put("chunk",e.chunkIndex()); p.put("start",e.start()); p.put("end",e.end());
                p.put("excerpt",e.excerpt()); p.put("confidence",e.confidence());
                tx.run("""
                    MATCH (a:GraphEntity {id:$from,projectId:$project}), (b:GraphEntity {id:$to,projectId:$project})
                    MERGE (r:GraphRelation {id:$id})
                    SET r.projectId=$project,r.type=$relationType,r.fromId=$from,r.toId=$to
                    MERGE (r)-[:FROM]->(a) MERGE (r)-[:TO]->(b)
                    MERGE (e:GraphEvidence {id:$evidence})
                    SET e.projectId=$project,e.origin='EXTRACTED',e.sourceType=$type,e.sourceId=$source,
                        e.sourceVersion=$version,e.chunkIndex=$chunk,e.start=$start,e.end=$end,
                        e.excerpt=$excerpt,e.confidence=$confidence,e.version=1
                    MERGE (e)-[:SUPPORTS]->(r)
                    """,p).consume();
            }
            return null;
        });
    }    @PreDestroy public synchronized void close() { if(driver!=null) driver.close(); }
}
