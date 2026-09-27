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
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "agentforge.security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "agentforge.agent-service.internal-token=test-only-internal-token",
    "agentforge.core-internal.token=test-only-core-token", "agentforge.graph.enabled=true"
})
class GraphApiIntegrationTest {
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
    void ownerProjectsStableEntityAndReadsItBack() throws Exception {
        var f = fixture();
        var body = Map.<String,Object>of("type","PROJECT", "externalId",f.project().toString(),
            "displayName","Ignored client label", "expectedVersion",0);
        var first = json.readTree(putEntity(f,body));
        assertThat(first.get("id").asText()).isEqualTo(f.project().toString());
        assertThat(first.get("displayName").asText()).isEqualTo("Graph");
        assertThat(first.get("version").asLong()).isEqualTo(1);
        assertThat(json.readTree(putEntity(f,body))).isEqualTo(first);
        mvc.perform(get(f.path()+"/entities").with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(f.project().toString()));
    }

    @Test
    void sourceBackedEntitiesRequireCurrentProjectVersionAndHaveStableIds() throws Exception {
        var f = fixture();
        var page = wiki.create(f.project(), f.actor(), "Architecture", "Core exposes /tasks.");
        var source = Map.<String,Object>of("type", "WIKI", "id", page.id(), "version", page.version());
        var body = Map.<String,Object>of("type", "SERVICE", "externalId", "core-api",
            "displayName", "Core API", "source", source, "expectedVersion", 0);

        var first = json.readTree(putEntity(f, body));
        assertThat(first.at("/source/id").asText()).isEqualTo(page.id().toString());

        var renamed = new java.util.LinkedHashMap<>(body);
        renamed.put("displayName", "Core HTTP API");
        renamed.put("expectedVersion", 1);
        var updated = json.readTree(putEntity(f, renamed));
        assertThat(updated.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(updated.get("version").asLong()).isEqualTo(2);

        wiki.update(f.project(), page.id(), f.actor(), "Architecture", "Changed", page.version());
        mvc.perform(put(f.path()+"/entities").with(f.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "API", "externalId", "tasks-v1",
                    "displayName", "Tasks", "source", source, "expectedVersion", 0))))
            .andExpect(status().isConflict());
    }

    @Test
    void sourceCannotCrossProjectAndBusinessIdentityMustMatchSource() throws Exception {
        var owner = fixture();
        var other = fixture();
        var page = wiki.create(other.project(), other.actor(), "Other", "Secret");
        var source = Map.<String,Object>of("type", "WIKI", "id", page.id(), "version", page.version());

        mvc.perform(put(owner.path()+"/entities").with(owner.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "SERVICE", "externalId", "foreign",
                    "displayName", "Foreign", "source", source, "expectedVersion", 0))))
            .andExpect(status().isNotFound());

        var localTask = tasks.create(owner.project(), owner.actor(), "Task", null, TaskStatus.TODO, TaskPriority.MEDIUM);
        var taskSource = Map.<String,Object>of("type", "TASK", "id", localTask.id(), "version", localTask.version());
        mvc.perform(put(owner.path()+"/entities").with(owner.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "TASK", "externalId", UUID.randomUUID().toString(),
                    "displayName", "Ignored", "source", taskSource, "expectedVersion", 0))))
            .andExpect(status().isBadRequest());
    }
    record RelationFixture(Fixture f, UUID service, UUID api, UUID source, long sourceVersion) {
        Map<String,Object> evidence(double confidence,long version) {
            return Map.of("source",Map.of("type","WIKI","id",source,"version",sourceVersion),
                "start",0,"end",19,"excerpt","Core exposes /tasks","confidence",confidence,"expectedVersion",version);
        }
        Map<String,Object> relation(Map<String,Object> evidence) {
            return Map.of("type","EXPOSES","fromId",service,"toId",api,"evidence",evidence);
        }
    }
    RelationFixture relationFixture() throws Exception {
        var f=fixture();
        var page=wiki.create(f.project(),f.actor(),"Architecture","Core exposes /tasks.");
        var source=Map.of("type","WIKI","id",page.id(),"version",page.version());
        var service=json.readTree(putEntity(f,Map.of("type","SERVICE","externalId","core","displayName","Core",
            "source",source,"expectedVersion",0)));
        var api=json.readTree(putEntity(f,Map.of("type","API","externalId","tasks","displayName","Tasks",
            "source",source,"expectedVersion",0)));
        return new RelationFixture(f,UUID.fromString(service.get("id").asText()),UUID.fromString(api.get("id").asText()),page.id(),page.version());
    }
    String putRelation(RelationFixture r, Map<String,Object> body) throws Exception {
        return mvc.perform(put(r.f().path()+"/relations").with(r.f().token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    @Test
    void relationRetainsIndependentEvidenceAndRetriesDoNotDuplicate() throws Exception {
        var r=relationFixture();
        var first=json.readTree(putRelation(r,r.relation(r.evidence(0.8,0))));
        assertThat(first.get("evidence").size()).isEqualTo(1);
        assertThat(json.readTree(putRelation(r,r.relation(r.evidence(0.8,0))))).isEqualTo(first);
        var secondPage=wiki.create(r.f().project(),r.f().actor(),"Other evidence","Core exposes /tasks.");
        var second=new java.util.LinkedHashMap<>(r.evidence(0.9,0));
        second.put("source",Map.of("type","WIKI","id",secondPage.id(),"version",secondPage.version()));
        var result=json.readTree(putRelation(r,r.relation(second)));
        assertThat(result.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(result.get("evidence").size()).isEqualTo(2);
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(r.f().token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("EXPOSES"))
            .andExpect(jsonPath("$.items[0].evidence.length()").value(2));
    }
    @Test
    void manualCleanupRequiresConfirmationAndLeavesBusinessSourcesIntact() throws Exception {
        var r=relationFixture();
        putRelation(r,r.relation(r.evidence(0.8,0)));
        mvc.perform(delete(r.f().path()).with(r.f().token())).andExpect(status().isBadRequest());
        mvc.perform(delete(r.f().path()).param("confirm","false").with(r.f().token())).andExpect(status().isBadRequest());
        mvc.perform(delete(r.f().path()).param("confirm","true").with(r.f().token())).andExpect(status().isNoContent());
        mvc.perform(get(r.f().path()+"/entities").with(r.f().token())).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertThat(wiki.get(r.f().project(),r.source(),r.f().actor()).content()).isEqualTo("Core exposes /tasks.");
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(r.f().token())).andExpect(status().isNotFound());
    }
    @Test
    void anonymousAndOtherUserCannotReadWriteOrClearProjectGraph() throws Exception {
        var r=relationFixture();
        var other=fixture();
        mvc.perform(get(r.f().path()+"/entities")).andExpect(status().isUnauthorized());
        mvc.perform(get(r.f().path()+"/entities").with(other.token())).andExpect(status().isForbidden());
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(other.token())).andExpect(status().isForbidden());
        mvc.perform(put(r.f().path()+"/relations").with(other.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(r.relation(r.evidence(0.8,0))))).andExpect(status().isForbidden());
        mvc.perform(put(r.f().path()+"/entities").with(other.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("type","PROJECT","externalId",r.f().project().toString(),"displayName","Graph","expectedVersion",0))))
            .andExpect(status().isForbidden());
        mvc.perform(delete(r.f().path()).param("confirm","true").with(other.token())).andExpect(status().isForbidden());
        mvc.perform(get(r.f().path()+"/entities").with(jwt().jwt(j -> j.subject(other.actor().userId().toString()).claim("roles",java.util.List.of("ADMIN")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }
    @Test
    void invalidDirectionUnknownEndpointsCrossProjectAndFabricatedEvidenceAreRejected() throws Exception {
        var r=relationFixture();
        var reversed=new java.util.LinkedHashMap<>(r.relation(r.evidence(0.8,0)));
        reversed.put("fromId",r.api()); reversed.put("toId",r.service());
        assertRelationStatus(r,reversed,400);
        var unknown=new java.util.LinkedHashMap<>(r.relation(r.evidence(0.8,0))); unknown.put("toId",UUID.randomUUID());
        assertRelationStatus(r,unknown,404);
        var foreign=relationFixture(); unknown.put("toId",foreign.api()); assertRelationStatus(r,unknown,404);
        var badSource=new java.util.LinkedHashMap<>(r.evidence(0.8,0));
        badSource.put("source",Map.of("type","WIKI","id",foreign.source(),"version",0));
        assertRelationStatus(r,r.relation(badSource),404);
        var badText=new java.util.LinkedHashMap<>(r.evidence(0.8,0)); badText.put("excerpt","Invented assertion");
        assertRelationStatus(r,r.relation(badText),400);
        badText=new java.util.LinkedHashMap<>(r.evidence(0.8,0)); badText.put("end",500); assertRelationStatus(r,r.relation(badText),400);
        badText=new java.util.LinkedHashMap<>(r.evidence(0.8,0)); badText.put("end",0); assertRelationStatus(r,r.relation(badText),400);
        for(Object confidence:java.util.List.of(-0.1,1.1,"NaN","Infinity","-Infinity")) {
            var evidence=new java.util.LinkedHashMap<>(r.evidence(0.8,0));evidence.put("confidence",confidence);
            assertRelationStatus(r,r.relation(evidence),400);
        }
        var missing=new java.util.LinkedHashMap<>(r.relation(r.evidence(0.8,0))); missing.remove("evidence"); assertRelationStatus(r,missing,400);
        var numeric=new java.util.LinkedHashMap<>(r.relation(r.evidence(0.8,0))); numeric.put("type",1); assertRelationStatus(r,numeric,400);
    }
    void assertRelationStatus(RelationFixture r,Map<String,Object> body,int status) throws Exception {
        mvc.perform(put(r.f().path()+"/relations").with(r.f().token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().is(status));
    }
    @Test
    void evidenceCompareAndSetRejectsStaleChangesButAllowsExactRetry() throws Exception {
        var r=relationFixture();
        var first=json.readTree(putRelation(r,r.relation(r.evidence(0.8,0))));
        assertRelationStatus(r,r.relation(r.evidence(0.9,0)),409);
        var updated=json.readTree(putRelation(r,r.relation(r.evidence(0.9,1))));
        assertThat(updated.at("/evidence/0/id").asText()).isEqualTo(first.at("/evidence/0/id").asText());
        assertThat(updated.at("/evidence/0/version").asLong()).isEqualTo(2);
        assertRelationStatus(r,r.relation(r.evidence(0.7,1)),409);
        assertThat(json.readTree(putRelation(r,r.relation(r.evidence(0.9,0))))).isEqualTo(updated);
    }
    @Test
    void expiredAndDeletedEvidenceIsNeverReturnedAsUsableProvenance() throws Exception {
        var r=relationFixture();
        var evidencePage=wiki.create(r.f().project(),r.f().actor(),"Evidence only","Core exposes /tasks.");
        var evidence=new java.util.LinkedHashMap<>(r.evidence(0.8,0));
        evidence.put("source",Map.of("type","WIKI","id",evidencePage.id(),"version",evidencePage.version()));
        putRelation(r,r.relation(evidence));
        var changed=wiki.update(r.f().project(),evidencePage.id(),r.f().actor(),"Evidence only","Changed",evidencePage.version());
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(r.f().token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertRelationStatus(r,r.relation(evidence),409);
        wiki.delete(r.f().project(),changed.id(),new AuthenticatedActor(r.f().actor().userId(),true),changed.version());
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(r.f().token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        wiki.update(r.f().project(),r.source(),r.f().actor(),"Architecture","New source",r.sourceVersion());
        mvc.perform(get(r.f().path()+"/entities").with(r.f().token())).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(r.f().token())).andExpect(status().isNotFound());
    }
    @Test
    void paginationIsBoundedAndNamesDoNotMergeDistinctEntities() throws Exception {
        var r=relationFixture();
        putEntity(r.f(),Map.of("type","SERVICE","externalId","another-core","displayName","Core",
            "source",Map.of("type","WIKI","id",r.source(),"version",r.sourceVersion()),"expectedVersion",0));
        var ids=new java.util.HashSet<String>();
        String after="";
        for(int page=0;page<5;page++) {
            var response=mvc.perform(get(r.f().path()+"/entities").param("limit","1").param("after",after).with(r.f().token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var result=json.readTree(response);
            for(var item:result.get("items")) assertThat(ids.add(item.get("id").asText())).isTrue();
            if(!result.hasNonNull("nextAfter")) break;
            after=result.get("nextAfter").asText();
        }
        assertThat(ids).hasSize(3);
        for(String limit:java.util.List.of("0","101","2147483647"))
            mvc.perform(get(r.f().path()+"/entities").param("limit",limit).with(r.f().token())).andExpect(status().isBadRequest());
        mvc.perform(get(r.f().path()+"/entities").param("after","MATCH (n)").with(r.f().token())).andExpect(status().isBadRequest());
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").param("limit","101").with(r.f().token())).andExpect(status().isBadRequest());
    }
    @Test
    void concurrentEvidenceRetriesProduceOneLogicalRelationAndOneEvidence() throws Exception {
        var r=relationFixture();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(() -> { start.await(); return putRelation(r,r.relation(r.evidence(0.8,0))); }));
            start.countDown();
            var ids=new java.util.HashSet<String>();
            for(var future:futures) {
                var response=json.readTree(future.get(20,java.util.concurrent.TimeUnit.SECONDS));
                ids.add(response.get("id").asText());
                assertThat(response.get("evidence").size()).isEqualTo(1);
                assertThat(response.at("/evidence/0/version").asLong()).isEqualTo(1);
            }
            assertThat(ids).hasSize(1);
        }
        mvc.perform(get(r.f().path()+"/entities/"+r.api()+"/neighbors").with(r.f().token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test
    void allDomainTypesAndAllowedDirectionsAreUsableWithTaskEvidence() throws Exception {
        var r=relationFixture();
        var f=r.f();
        var task=tasks.create(f.project(),f.actor(),"Change API","Core exposes /tasks.",TaskStatus.TODO,TaskPriority.MEDIUM);
        var taskSource=Map.of("type","TASK","id",task.id(),"version",task.version());
        var wikiId=json.readTree(putEntity(f,Map.of("type","WIKI","externalId",r.source().toString(),"displayName","Ignored",
            "source",Map.of("type","WIKI","id",r.source(),"version",r.sourceVersion()),"expectedVersion",0))).get("id").asText();
        var taskId=json.readTree(putEntity(f,Map.of("type","TASK","externalId",task.id().toString(),"displayName","Ignored",
            "source",taskSource,"expectedVersion",0))).get("id").asText();
        var issueId=json.readTree(putEntity(f,Map.of("type","ISSUE","externalId","ISSUE-1","displayName","Issue",
            "source",taskSource,"expectedVersion",0))).get("id").asText();
        putEntity(f,Map.of("type","PROJECT","externalId",f.project().toString(),"displayName","Ignored","expectedVersion",0));
        var evidence=new java.util.LinkedHashMap<>(r.evidence(1.0,0));evidence.put("source",taskSource);evidence.put("chunkIndex",0);
        for(var direction:java.util.List.of(
            java.util.List.of("CONTAINS",f.project().toString(),r.service().toString()),
            java.util.List.of("CONTAINS",f.project().toString(),r.api().toString()),
            java.util.List.of("CONTAINS",f.project().toString(),wikiId),
            java.util.List.of("CONTAINS",f.project().toString(),taskId),
            java.util.List.of("CONTAINS",f.project().toString(),issueId),
            java.util.List.of("DESCRIBES",wikiId,r.service().toString()),
            java.util.List.of("DESCRIBES",wikiId,r.api().toString()),
            java.util.List.of("DESCRIBES",wikiId,taskId),java.util.List.of("DESCRIBES",wikiId,issueId),
            java.util.List.of("MODIFIES",taskId,r.service().toString()),java.util.List.of("MODIFIES",taskId,r.api().toString()),
            java.util.List.of("MODIFIES",taskId,wikiId),java.util.List.of("AFFECTS",issueId,r.service().toString()),
            java.util.List.of("AFFECTS",issueId,r.api().toString()),java.util.List.of("AFFECTS",issueId,taskId))) {
            var response=json.readTree(putRelation(r,Map.of("type",direction.get(0),"fromId",direction.get(1),"toId",direction.get(2),"evidence",evidence)));
            assertThat(response.at("/evidence/0/source/type").asText()).isEqualTo("TASK");
        }
    }
    @Test
    void concurrentEntityRetriesProduceOneStableEntity() throws Exception {
        var f=fixture();
        var page=wiki.create(f.project(),f.actor(),"Source","Core exposes /tasks.");
        var body=Map.<String,Object>of("type","SERVICE","externalId","same-id","displayName","Same name",
            "source",Map.of("type","WIKI","id",page.id(),"version",page.version()),"expectedVersion",0);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(() -> { start.await();return putEntity(f,body); }));
            start.countDown();
            var ids=new java.util.HashSet<String>();
            for(var future:futures) {
                var result=json.readTree(future.get(20,java.util.concurrent.TimeUnit.SECONDS));
                ids.add(result.get("id").asText());assertThat(result.get("version").asLong()).isEqualTo(1);
            }
            assertThat(ids).hasSize(1);
        }
        mvc.perform(get(f.path()+"/entities").with(f.token())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        var stale=new java.util.LinkedHashMap<>(body);stale.put("displayName","Changed");
        mvc.perform(put(f.path()+"/entities").with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(stale))).andExpect(status().isConflict());
    }
    @Test
    void neighborhoodTruncatesEvidenceAndSignalsMoreWithoutUnboundedExpansion() throws Exception {
        var r=relationFixture();
        for(int i=0;i<21;i++) {
            var page=wiki.create(r.f().project(),r.f().actor(),"Evidence "+i,"Core exposes /tasks.");
            var evidence=new java.util.LinkedHashMap<>(r.evidence(0.8,0));
            evidence.put("source",Map.of("type","WIKI","id",page.id(),"version",page.version()));
            putRelation(r,r.relation(evidence));
        }
        mvc.perform(get(r.f().path()+"/entities/"+r.service()+"/neighbors").with(r.f().token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].evidence.length()").value(20))
            .andExpect(jsonPath("$.items[0].hasMoreEvidence").value(true));
    }
    @Test
    void realHttpSocketAndSignedJwtCanWriteReadAndRejectUnauthorizedGraphRequests() throws Exception {
        var registration=auth.register(UUID.randomUUID()+"@graph-http.test","HTTP owner","test-password");
        var actor=new AuthenticatedActor(registration.user().id(),false);
        var project=projects.createProject(actor,"HTTP graph",null);
        String path="http://127.0.0.1:"+port+"/api/v1/projects/"+project.id()+"/graph/entities";
        try(var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()) {
            var body=json.writeValueAsString(Map.of("type","PROJECT","externalId",project.id(),"displayName","Ignored","expectedVersion",0));
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(path)).timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization","Bearer "+registration.token().value()).header("Content-Type","application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("id").asText()).isEqualTo(project.id().toString());
            var read=java.net.http.HttpRequest.newBuilder(java.net.URI.create(path)).timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization","Bearer "+registration.token().value()).GET().build();
            assertThat(json.readTree(client.send(read,java.net.http.HttpResponse.BodyHandlers.ofString()).body()).get("items").size()).isEqualTo(1);
            var anonymous=java.net.http.HttpRequest.newBuilder(java.net.URI.create(path)).timeout(java.time.Duration.ofSeconds(15)).GET().build();
            assertThat(client.send(anonymous,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        }
    }
    @Test
    void fractionalOrStringVersionsCannotBeCoercedToAnotherVersion() throws Exception {
        var f=fixture();
        for(Object version:java.util.List.of(0.5,"0")) {
            mvc.perform(put(f.path()+"/entities").with(f.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type","PROJECT","externalId",f.project(),"displayName","Graph","expectedVersion",version))))
                .andExpect(status().isBadRequest());
        }
        var page=wiki.create(f.project(),f.actor(),"Source","Text");
        mvc.perform(put(f.path()+"/entities").with(f.token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("type","SERVICE","externalId","fractional","displayName","Service","expectedVersion",0,
                "source",Map.of("type","WIKI","id",page.id(),"version",0.5)))))
            .andExpect(status().isBadRequest());
    }
    @Test
    void concurrentCleanupNeverTurnsNeighborReadIntoServerError() throws Exception {
        for (int round=0; round<8; round++) {
            var r=relationFixture();
            for (int i=0; i<30; i++) {
                var id=json.readTree(putEntity(r.f(),Map.of("type","SERVICE","externalId","clear-"+i,
                    "displayName","Service","expectedVersion",0,"source",Map.of("type","WIKI","id",r.source(),"version",r.sourceVersion())))).get("id").asText();
                putRelation(r,Map.of("type","EXPOSES","fromId",id,"toId",r.api(),"evidence",r.evidence(0.8,0)));
            }
            var start=new java.util.concurrent.CountDownLatch(1);
            try (var executor=java.util.concurrent.Executors.newFixedThreadPool(4)) {
                var readers=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
                for (int i=0; i<3; i++) readers.add(executor.submit(() -> {
                    start.await();
                    return mvc.perform(get(r.f().path()+"/entities/"+r.api()+"/neighbors")
                        .param("limit","100").with(r.f().token())).andReturn().getResponse().getStatus();
                }));
                var clear=executor.submit(() -> {
                    start.await();
                    java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                    return mvc.perform(delete(r.f().path()).param("confirm","true").with(r.f().token()))
                        .andReturn().getResponse().getStatus();
                });
                start.countDown();
                assertThat(clear.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
                for (var reader:readers) assertThat(reader.get(20,java.util.concurrent.TimeUnit.SECONDS)).isIn(200,404);
            }
        }
    }
    @Test
    void sourceChangingAfterGraphCommitCannotReturnEmptySuccess() throws Exception {
        var r=relationFixture();
        org.mockito.Mockito.doAnswer(invocation -> {
            var saved=invocation.callRealMethod();
            wiki.update(r.f().project(),r.source(),r.f().actor(),"Architecture","Changed",r.sourceVersion());
            return saved;
        }).when(graphStore).putRelation(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(put(r.f().path()+"/relations").with(r.f().token()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(r.relation(r.evidence(0.8,0)))))
            .andExpect(status().isConflict());
    }
}
