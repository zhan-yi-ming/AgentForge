package com.agentforge.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.agentforge.core.graph.domain.GraphStore;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "agentforge.security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "agentforge.agent-service.internal-token=test-only-internal-token",
    "agentforge.core-internal.token=test-only-core-token", "agentforge.graph.enabled=true",
    "agentforge.graph.sync-interval-ms=200"
})
class GraphExtractionIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26-community")
        .withAdminPassword("graph-test-only-password");
    @DynamicPropertySource
    static void graphProperties(DynamicPropertyRegistry r) {
        r.add("agentforge.graph.uri", NEO4J::getBoltUrl);
        r.add("agentforge.graph.password", () -> "graph-test-only-password");
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @MockitoSpyBean GraphStore graphStore;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthenticationService auth;
    @Autowired ProjectService projects;
    @Autowired WikiPageService wiki;
    @Autowired TaskService tasks;
    record Fixture(UUID project, AuthenticatedActor actor) {
        String path() { return "/api/v1/projects/" + project + "/graph"; }
        RequestPostProcessor token() { return jwt().jwt(j -> j.subject(actor.userId().toString()).claim("roles", java.util.List.of("USER"))); }
    }
    Fixture fixture() {
        var user = auth.register(UUID.randomUUID() + "@graph.test", "Graph", "test-password");
        var actor = new AuthenticatedActor(user.user().id(), false);
        return new Fixture(projects.createProject(actor, "Graph", null).id(), actor);
    }
    String putEntity(Fixture f, Map<String,Object> body) throws Exception {
        return mvc.perform(put(f.path()+"/entities").with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    @Test
    void wikiCreateExtractsSourceBackedRelation() throws Exception {
        var f = fixture();
        var created = mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Architecture","content","Service: billing"))))
            .andExpect(status().isCreated()).andReturn();
        var sourceId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        boolean found = false;
        while (System.nanoTime() < deadline) {
            var entities = json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for (var entity : entities.get("items")) {
                if (entity.get("type").asText().equals("WIKI") && entity.get("externalId").asText().equals(sourceId)) {
                    var neighbors = json.readTree(mvc.perform(get(f.path()+"/entities/"+entity.get("id").asText()+"/neighbors")
                        .with(f.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                    for (var relation : neighbors.get("items")) {
                        if (relation.get("type").asText().equals("DESCRIBES")
                            && relation.get("evidence").get(0).get("excerpt").asText().equals("Service: billing")) found = true;
                    }
                }
            }
            if (found) break;
            Thread.sleep(100);
        }
        assertThat(found).isTrue();
    }
    @Test
    void ownerCanQueueRebuildButOtherUserCannot() throws Exception {
        var f = fixture();
        var other = fixture();
        mvc.perform(post(f.path()+"/extraction/rebuild").with(other.token()))
            .andExpect(status().isForbidden());
        mvc.perform(post(f.path()+"/extraction/rebuild").with(f.token()))
            .andExpect(status().isAccepted());
    }
    private boolean hasEntity(Fixture f, String type, String name) throws Exception {
        var entities=json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (var entity:entities.get("items")) {
            if (entity.get("type").asText().equals(type) && entity.get("displayName").asText().equals(name)) return true;
        }
        return false;
    }
    private void awaitEntity(Fixture f, String type, String name) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime()<deadline) {
            if (hasEntity(f,type,name)) return;
            Thread.sleep(100);
        }
        assertThat(hasEntity(f,type,name)).as(type+" "+name+" projected").isTrue();
    }
    @Test
    void wikiUpdateAndDeleteReplaceExtractedClaims() throws Exception {
        var f=fixture();
        String path="/api/v1/projects/"+f.project()+"/wiki-pages";
        var created=json.readTree(mvc.perform(post(path).with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Architecture","content","Service: billing"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        awaitEntity(f,"SERVICE","billing");
        String id=created.get("id").asText();
        var updated=json.readTree(mvc.perform(put(path+"/"+id).with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Architecture","content","Service: orders",
                "version",created.get("version").asLong()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        awaitEntity(f,"SERVICE","orders");
        assertThat(hasEntity(f,"SERVICE","billing")).isFalse();
        mvc.perform(delete(path+"/"+id).param("version",updated.get("version").asText())
            .with(jwt().jwt(j -> j.subject(f.actor().userId().toString()).claim("roles",java.util.List.of("ADMIN")))))
            .andExpect(status().isNoContent());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long pending=1;
        while (System.nanoTime()<deadline) {
            var sync=json.readTree(mvc.perform(get(f.path()+"/extraction/status").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            pending=sync.get("pending").asLong();
            if (pending==0) break;
            Thread.sleep(100);
        }
        assertThat(pending).as("delete projection processed").isZero();
        assertThat(hasEntity(f,"SERVICE","orders")).isFalse();
    }
    @Test
    void taskDescriptionCreatesModifiesRelation() throws Exception {
        var f=fixture();
        var created=json.readTree(mvc.perform(post("/api/v1/projects/"+f.project()+"/tasks").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Implement billing","description","Modifies Service: billing"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id=created.get("id").asText();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        boolean found=false;
        while(System.nanoTime()<deadline && !found) {
            var entities=json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for(var entity:entities.get("items")) {
                if(entity.get("type").asText().equals("TASK") && entity.get("externalId").asText().equals(id)) {
                    var neighbors=json.readTree(mvc.perform(get(f.path()+"/entities/"+entity.get("id").asText()+"/neighbors")
                        .with(f.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                    for(var relation:neighbors.get("items"))
                        if(relation.get("type").asText().equals("MODIFIES")
                            && relation.get("evidence").get(0).get("excerpt").asText().equals("Modifies Service: billing")) found=true;
                }
            }
            if(!found) Thread.sleep(100);
        }
        assertThat(found).isTrue();
    }
    @Test
    void ownerCanObserveSourceSyncCompletion() throws Exception {
        var f=fixture();
        mvc.perform(get(f.path()+"/extraction/status").with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(0))
            .andExpect(jsonPath("$.retrying").value(0));
        mvc.perform(get(f.path()+"/extraction/status").with(fixture().token()))
            .andExpect(status().isForbidden());
    }
    @Test
    void rebuildRestoresClearedProjectionFromCurrentWiki() throws Exception {
        var f=fixture();
        mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Architecture","content","Service: billing"))))
            .andExpect(status().isCreated());
        awaitEntity(f,"SERVICE","billing");
        mvc.perform(delete(f.path()).param("confirm","true").with(f.token()))
            .andExpect(status().isNoContent());
        assertThat(hasEntity(f,"SERVICE","billing")).isFalse();
        mvc.perform(post(f.path()+"/extraction/rebuild").with(f.token()))
            .andExpect(status().isAccepted());
        awaitEntity(f,"SERVICE","billing");
    }
    @Test
    void apiClaimsRequireExplicitMethodAndPath() throws Exception {
        var f=fixture();
        mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Endpoints",
                "content","API: nonsense\nAPI: GET /orders"))))
            .andExpect(status().isCreated());
        awaitEntity(f,"API","GET /orders");
        assertThat(hasEntity(f,"API","nonsense")).isFalse();
    }
    @Test
    void graphFailureKeepsRetryableSourceAndRebuildRecovers() throws Exception {
        var f=fixture();
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (fail.get()) throw new com.agentforge.core.shared.error.ServiceUnavailableException("test graph failure");
            return invocation.callRealMethod();
        }).when(graphStore).replaceSource(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Failure source","content","Service: billing"))))
            .andExpect(status().isCreated());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long retrying=0;
        while(System.nanoTime()<deadline) {
            var statusBody=json.readTree(mvc.perform(get(f.path()+"/extraction/status").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            retrying=statusBody.get("retrying").asLong();
            if(retrying>0) break;
            Thread.sleep(100);
        }
        assertThat(retrying).isEqualTo(1);
        assertThat(hasEntity(f,"SERVICE","billing")).isFalse();
        fail.set(false);
        mvc.perform(post(f.path()+"/extraction/rebuild").with(f.token()))
            .andExpect(status().isAccepted());
        awaitEntity(f,"SERVICE","billing");
    }
    @Test
    void refreshingOneSourcePreservesManualRelationWithIndependentEvidence() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"Source","Service: billing");
        var proof=wiki.create(f.project(),f.actor(),"Proof","Service: manual");
        awaitEntity(f,"WIKI","Source");
        String sourceEntityId=null;
        var entities=json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(var entity:entities.get("items"))
            if(entity.get("type").asText().equals("WIKI")
                && entity.get("externalId").asText().equals(first.id().toString())) sourceEntityId=entity.get("id").asText();
        assertThat(sourceEntityId).isNotNull();
        var source=Map.of("type","WIKI","id",proof.id(),"version",proof.version());
        var manual=json.readTree(putEntity(f,Map.of("type","SERVICE","externalId","manual-service",
            "displayName","Manual service","source",source,"expectedVersion",0)));
        mvc.perform(put(f.path()+"/relations").with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("type","DESCRIBES","fromId",sourceEntityId,
                "toId",manual.get("id").asText(),"evidence",Map.of("source",source,"start",0,
                    "end",15,"excerpt","Service: manual","confidence",1.0,"expectedVersion",0)))))
            .andExpect(status().isOk());
        mvc.perform(put(f.path()+"/relations").with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("type","DESCRIBES","fromId",sourceEntityId,
                "toId",manual.get("id").asText(),"evidence",Map.of("source",Map.of("type","WIKI",
                    "id",first.id(),"version",first.version()),"start",0,
                    "end",16,"excerpt","Service: billing","confidence",1.0,"expectedVersion",0)))))
            .andExpect(status().isOk());        wiki.update(f.project(),first.id(),f.actor(),"Source","Service: billing2",first.version());
        awaitEntity(f,"SERVICE","billing2");
        var neighbors=json.readTree(mvc.perform(get(f.path()+"/entities/"+sourceEntityId+"/neighbors")
            .with(f.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean manualPresent=false;
        for(var relation:neighbors.get("items"))
            if(relation.get("toId").asText().equals(manual.get("id").asText())
                && relation.get("evidence").get(0).get("source").get("id").asText().equals(proof.id().toString()))
                manualPresent=true;
        assertThat(manualPresent).isTrue();        try(var driver=org.neo4j.driver.GraphDatabase.driver(NEO4J.getBoltUrl(),
            org.neo4j.driver.AuthTokens.basic("neo4j","graph-test-only-password"));
            var session=driver.session()) {
            long stale=session.run("""
                MATCH (e:GraphEvidence {projectId:$project,sourceType:'WIKI',sourceId:$source,sourceVersion:$version})
                RETURN count(e) AS count
                """,Map.of("project",f.project().toString(),"source",first.id().toString(),"version",first.version()))
                .single().get("count").asLong();
            assertThat(stale).as("old source version evidence removed").isZero();
        }
    }
    @Test
    void repeatedClaimKeepsOneRelationAndTwoExactEvidenceSpans() throws Exception {
        var f=fixture();
        var body=json.readTree(mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Repeated","content",
                "Service: billing\nService: billing"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        awaitEntity(f,"SERVICE","billing");
        var entities=json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String sourceEntityId=null;
        for(var entity:entities.get("items"))
            if(entity.get("type").asText().equals("WIKI")
                && entity.get("externalId").asText().equals(body.get("id").asText())) sourceEntityId=entity.get("id").asText();
        assertThat(sourceEntityId).isNotNull();
        var neighbors=json.readTree(mvc.perform(get(f.path()+"/entities/"+sourceEntityId+"/neighbors")
            .with(f.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(neighbors.get("items").size()).isEqualTo(1);
        var evidence=neighbors.get("items").get(0).get("evidence");
        assertThat(evidence.size()).isEqualTo(2);
        assertThat(evidence.get(0).get("excerpt").asText()).isEqualTo("Service: billing");
        assertThat(evidence.get(1).get("excerpt").asText()).isEqualTo("Service: billing");
        assertThat(java.util.Set.of(evidence.get(0).get("start").asInt(), evidence.get(1).get("start").asInt()))
            .containsExactlyInAnyOrder(0,17);
    }
    @Test
    void deletingWikiPhysicallyRemovesManualEvidenceFromThatSource() throws Exception {
        var f=fixture();
        var evidencePage=wiki.create(f.project(),f.actor(),"Evidence","Service: billing");
        var endpoints=wiki.create(f.project(),f.actor(),"Endpoints","Service: context");
        var source=Map.of("type","WIKI","id",endpoints.id(),"version",endpoints.version());
        var service=json.readTree(putEntity(f,Map.of("type","SERVICE","externalId","manual-service",
            "displayName","Manual service","source",source,"expectedVersion",0)));
        var api=json.readTree(putEntity(f,Map.of("type","API","externalId","manual-api",
            "displayName","Manual API","source",source,"expectedVersion",0)));
        mvc.perform(put(f.path()+"/relations").with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("type","EXPOSES","fromId",service.get("id").asText(),
                "toId",api.get("id").asText(),"evidence",Map.of("source",Map.of("type","WIKI",
                    "id",evidencePage.id(),"version",evidencePage.version()),"start",0,
                    "end",16,"excerpt","Service: billing","confidence",1.0,"expectedVersion",0)))))
            .andExpect(status().isOk());
        wiki.delete(f.project(),evidencePage.id(),
            new AuthenticatedActor(f.actor().userId(),true),evidencePage.version());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long pending=1;
        while(System.nanoTime()<deadline) {
            var state=json.readTree(mvc.perform(get(f.path()+"/extraction/status").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            pending=state.get("pending").asLong();
            if(pending==0) break;
            Thread.sleep(100);
        }
        assertThat(pending).isZero();
        try(var driver=org.neo4j.driver.GraphDatabase.driver(NEO4J.getBoltUrl(),
            org.neo4j.driver.AuthTokens.basic("neo4j","graph-test-only-password"));
            var session=driver.session()) {
            long remaining=session.run("""
                MATCH (e:GraphEvidence {projectId:$project,sourceType:'WIKI',sourceId:$source})
                RETURN count(e) AS count
                """, Map.of("project",f.project().toString(),"source",evidencePage.id().toString()))
                .single().get("count").asLong();
            assertThat(remaining).isZero();
        }
    }
    @Test
    void oversizedExcerptKeepsProjectionRetryableWithoutPartialPublish() throws Exception {
        var f=fixture();
        mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Long evidence",
                "content"," ".repeat(2000)+"Service: billing"))))
            .andExpect(status().isCreated());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long retrying=0;
        while(System.nanoTime()<deadline) {
            var state=json.readTree(mvc.perform(get(f.path()+"/extraction/status").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            retrying=state.get("retrying").asLong();
            if(retrying>0) break;
            Thread.sleep(100);
        }
        assertThat(retrying).isEqualTo(1);
        assertThat(hasEntity(f,"SERVICE","billing")).isFalse();
    }
    @Test
    void maximalTargetNameKeepsExternalIdWithinGraphContract() throws Exception {
        var f=fixture();
        String name="x".repeat(200);
        mvc.perform(post("/api/v1/projects/"+f.project()+"/wiki-pages").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Long target","content","Service: "+name))))
            .andExpect(status().isCreated());
        awaitEntity(f,"SERVICE",name);
        var entities=json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for(var entity:entities.get("items"))
            if(entity.get("type").asText().equals("SERVICE") && entity.get("displayName").asText().equals(name)) {
                assertThat(entity.get("externalId").asText().length()).isLessThanOrEqualTo(200);
                return;
            }
        throw new AssertionError("projected service not found");
    }
    @Test
    void taskUpdateAndDeleteReplaceExtractedClaims() throws Exception {
        var f=fixture();
        String path="/api/v1/projects/"+f.project()+"/tasks";
        var created=json.readTree(mvc.perform(post(path).with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Implement","description","Modifies Service: billing"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        awaitEntity(f,"SERVICE","billing");
        String id=created.get("id").asText();
        var updated=json.readTree(mvc.perform(put(path+"/"+id).with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title","Implement","description","Modifies API: GET /orders",
                "status","TODO","priority","MEDIUM","version",created.get("version").asLong()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        awaitEntity(f,"API","GET /orders");
        assertThat(hasEntity(f,"SERVICE","billing")).isFalse();
        mvc.perform(delete(path+"/"+id).param("version",updated.get("version").asText())
            .with(jwt().jwt(j -> j.subject(f.actor().userId().toString()).claim("roles",java.util.List.of("ADMIN")))))
            .andExpect(status().isNoContent());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long pending=1;
        while(System.nanoTime()<deadline) {
            var state=json.readTree(mvc.perform(get(f.path()+"/extraction/status").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            pending=state.get("pending").asLong();
            if(pending==0) break;
            Thread.sleep(100);
        }
        assertThat(pending).isZero();
        assertThat(hasEntity(f,"API","GET /orders")).isFalse();
    }}
