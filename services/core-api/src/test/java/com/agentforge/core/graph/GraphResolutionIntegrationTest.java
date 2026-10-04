package com.agentforge.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "agentforge.security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "agentforge.agent-service.internal-token=test-only-internal-token",
    "agentforge.core-internal.token=test-only-core-token", "agentforge.graph.enabled=true",
    "agentforge.graph.sync-interval-ms=200"
})
class GraphResolutionIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.26-community")
        .withAdminPassword("graph-test-only-password");
    @DynamicPropertySource static void graphProperties(DynamicPropertyRegistry r) {
        r.add("agentforge.graph.uri", NEO4J::getBoltUrl);
        r.add("agentforge.graph.password", () -> "graph-test-only-password");
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agentforge.core.graph.application.GraphResolutionAdvisor advisor;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agentforge.core.agent.application.AiUsageQuota aiUsageQuota;
    @Autowired com.agentforge.core.graph.application.GraphService graph;
    @org.junit.jupiter.api.BeforeEach
    void noExternalModelByDefault() {
        org.mockito.Mockito.when(advisor.suggest(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new com.agentforge.core.graph.application.GraphResolutionAdvisor.Advice(null,0));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthenticationService auth;
    @Autowired ProjectService projects;
    @Autowired WikiPageService wiki;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    record Fixture(UUID project, AuthenticatedActor actor) {
        String path() { return "/api/v1/projects/"+project+"/graph"; }
        RequestPostProcessor token() { return jwt().jwt(j -> j.subject(actor.userId().toString()).claim("roles",java.util.List.of("USER"))); }
    }
    Fixture fixture() {
        var user=auth.register(UUID.randomUUID()+"@resolution.test","Resolution","test-password");
        var actor=new AuthenticatedActor(user.user().id(),false);
        return new Fixture(projects.createProject(actor,"Resolution",null).id(),actor);
    }
    String awaitService(Fixture f,UUID sourceId) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline) {
            var body=json.readTree(mvc.perform(get(f.path()+"/entities").with(f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            for(var entity:body.get("items"))
                if(entity.get("type").asText().equals("SERVICE")
                    && entity.get("externalId").asText().contains(sourceId.toString())) return entity.get("id").asText();
            Thread.sleep(100);
        }
        throw new AssertionError("source service not projected");
    }
    @Test
    void suggestionOffersSameProjectCandidateWithoutMerging() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing");
        String sourceEntity=awaitService(f,first.id());
        String candidate=awaitService(f,second.id());
        var response=mvc.perform(post(f.path()+"/resolution/suggestions").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("entityId",sourceEntity))))
            .andExpect(status().isOk()).andReturn();
        var suggestion=json.readTree(response.getResponse().getContentAsString());
        assertThat(suggestion.get("reviewRequired").asBoolean()).isTrue();
        assertThat(suggestion.get("candidates").toString()).contains(candidate);
        assertThat(sourceEntity).isNotEqualTo(candidate);
        org.mockito.Mockito.verify(aiUsageQuota).consume(f.actor().userId());
    }
    @Test
    void suggestionWithoutCandidatesDoesNotConsumeQuotaOrCallAdvisor() throws Exception {
        var f=fixture();
        var page=wiki.create(f.project(),f.actor(),"Only","Service: UniqueName");
        String sourceEntity=awaitService(f,page.id());
        mvc.perform(post(f.path()+"/resolution/suggestions").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("entityId",sourceEntity))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidates.length()").value(0));
        org.mockito.Mockito.verifyNoInteractions(aiUsageQuota);
        org.mockito.Mockito.verifyNoInteractions(advisor);
    }
    @Test
    void exhaustedQuotaRejectsSuggestionBeforeAdvisor() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        wiki.create(f.project(),f.actor(),"Second","Service: Billing");
        String sourceEntity=awaitService(f,first.id());
        org.mockito.Mockito.doThrow(new com.agentforge.core.shared.error.RateLimitExceededException("limit"))
            .when(aiUsageQuota).consume(f.actor().userId());
        mvc.perform(post(f.path()+"/resolution/suggestions").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("entityId",sourceEntity))))
            .andExpect(status().isTooManyRequests());
        org.mockito.Mockito.verifyNoInteractions(advisor);
    }
    @Test
    void unauthenticatedSuggestionDoesNotConsumeQuota() throws Exception {
        mvc.perform(post("/api/v1/projects/"+UUID.randomUUID()+"/graph/resolution/suggestions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("entityId",UUID.randomUUID()))))
            .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(aiUsageQuota);
    }
    @Test
    void humanCanConfirmAndReadCanonicalMapping() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing Platform");
        String member=awaitService(f,first.id());
        String canonical=awaitService(f,second.id());
        var body=Map.of("canonicalEntityId",canonical,"canonicalName","Billing Platform",
            "aliases",java.util.List.of("Billing"),"metadata",Map.of("team","payments"),
            "confidence",0.92,"expectedVersion",0,
            "sourceVersion",graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version(),
            "canonicalSourceVersion",graph.entity(f.project(),UUID.fromString(canonical),f.actor()).source().version());
        mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canonicalEntityId").value(canonical))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canonicalName").value("Billing Platform"))
            .andExpect(jsonPath("$.aliases[0]").value("Billing"))
            .andExpect(jsonPath("$.metadata.team").value("payments"));
    }
    @Test
    void wrongMergeCanBeRevertedWithoutChangingSourceEntities() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing Platform");
        String member=awaitService(f,first.id());
        String canonical=awaitService(f,second.id());
        var body=Map.of("canonicalEntityId",canonical,"canonicalName","Billing Platform",
            "aliases",java.util.List.of("Billing"),"metadata",Map.of("team","payments"),
            "confidence",0.92,"expectedVersion",0,
            "sourceVersion",graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version(),
            "canonicalSourceVersion",graph.entity(f.project(),UUID.fromString(canonical),f.actor()).source().version());
        mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk());
        mvc.perform(delete(f.path()+"/resolution/decisions/"+member)
            .param("expectedVersion","1").with(f.token()))
            .andExpect(status().isNoContent());
        mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNMAPPED"))
            .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isConflict());
        assertThat(awaitService(f,first.id())).isEqualTo(member);
        assertThat(awaitService(f,second.id())).isEqualTo(canonical);
    }
    @Test
    void graphUsesOnlyAllowlistedAgentAdviceWithoutWritingMapping() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing Platform");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing Service");
        String member=awaitService(f,first.id());
        String candidate=awaitService(f,second.id());
        org.mockito.Mockito.when(advisor.suggest(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("SERVICE"),
            org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new com.agentforge.core.graph.application.GraphResolutionAdvisor.Advice(UUID.fromString(candidate),0.72));
        var response=mvc.perform(post(f.path()+"/resolution/suggestions").with(f.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("entityId",member))))
            .andExpect(status().isOk()).andReturn();
        var suggestion=json.readTree(response.getResponse().getContentAsString());
        assertThat(suggestion.get("recommendedCandidateId").asText()).isEqualTo(candidate);
        assertThat(suggestion.get("confidence").asDouble()).isEqualTo(0.72);
        mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNMAPPED"));
    }
    @Test
    void confirmationRejectsStaleSourceVersions() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing Platform");
        String member=awaitService(f,first.id());
        String canonical=awaitService(f,second.id());
        var body=Map.of("canonicalEntityId",canonical,"canonicalName","Billing Platform",
            "aliases",java.util.List.of("Billing"),"metadata",Map.of("team","payments"),
            "confidence",0.92,"expectedVersion",0,"sourceVersion",Long.MAX_VALUE,
            "canonicalSourceVersion",Long.MAX_VALUE);
        mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isConflict());
        mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNMAPPED"));
    }
    @Test
    void canonicalNameAndMetadataCanBeEditedWithAuditAndCas() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing Platform");
        String member=awaitService(f,first.id());
        String canonical=awaitService(f,second.id());
        long memberSource=graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version();
        long anchorSource=graph.entity(f.project(),UUID.fromString(canonical),f.actor()).source().version();
        var confirm=Map.of("canonicalEntityId",canonical,"canonicalName","Billing Platform",
            "aliases",java.util.List.of("Billing"),"metadata",Map.of("team","payments"),
            "confidence",0.92,"expectedVersion",0,"sourceVersion",memberSource,
            "canonicalSourceVersion",anchorSource);
        mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(confirm)))
            .andExpect(status().isOk());
        mvc.perform(get(f.path()+"/resolution/canonicals/"+canonical).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        var edit=Map.of("canonicalName","Billing Core","metadata",Map.of("team","core"),
            "sourceVersion",anchorSource,"expectedVersion",1);
        mvc.perform(put(f.path()+"/resolution/canonicals/"+canonical).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canonicalName").value("Billing Core"))
            .andExpect(jsonPath("$.metadata.team").value("core"));
        mvc.perform(put(f.path()+"/resolution/canonicals/"+canonical).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("canonicalName","Billing Other","metadata",Map.of("team","other"),
                    "sourceVersion",anchorSource,"expectedVersion",1))))
            .andExpect(status().isConflict());
    }
    @Test
    void projectScopeRejectsForeignCanonicalAndHidesForeignCandidates() throws Exception {
        var first=fixture(); var other=fixture();
        var source=wiki.create(first.project(),first.actor(),"Source","Service: Billing");
        var foreign=wiki.create(other.project(),other.actor(),"Foreign","Service: Billing");
        String member=awaitService(first,source.id());
        String foreignId=awaitService(other,foreign.id());
        var response=mvc.perform(post(first.path()+"/resolution/suggestions").with(first.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("entityId",member))))
            .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(foreignId);
        var body=Map.of("canonicalEntityId",foreignId,"canonicalName","Billing",
            "aliases",java.util.List.of("Bill"),"metadata",Map.of("team","payments"),
            "confidence",0.9,"expectedVersion",0,
            "sourceVersion",graph.entity(first.project(),UUID.fromString(member),first.actor()).source().version(),
            "canonicalSourceVersion",graph.entity(other.project(),UUID.fromString(foreignId),other.actor()).source().version());
        mvc.perform(put(first.path()+"/resolution/decisions/"+member).with(first.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isNotFound());
        mvc.perform(get(other.path()+"/resolution/decisions/"+foreignId).with(first.token()))
            .andExpect(status().isForbidden());
    }
    @Test
    void sourceChangeHidesExistingMemberAlias() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Billing Platform");
        String member=awaitService(f,first.id());
        String canonical=awaitService(f,second.id());
        var body=Map.of("canonicalEntityId",canonical,"canonicalName","Billing Platform",
            "aliases",java.util.List.of("Billing"),"metadata",Map.of("team","payments"),
            "confidence",0.92,"expectedVersion",0,
            "sourceVersion",graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version(),
            "canonicalSourceVersion",graph.entity(f.project(),UUID.fromString(canonical),f.actor()).source().version());
        mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk());
        wiki.update(f.project(),first.id(),f.actor(),"First","Service: Billing\nUpdated notes",first.version());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long refreshed=-1;
        while(System.nanoTime()<deadline) {
            try { refreshed=graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version(); }
            catch(com.agentforge.core.shared.error.ResourceNotFoundException pending) { /* sync in progress */ }
            if(refreshed>first.version()) break;
            Thread.sleep(100);
        }
        assertThat(refreshed).isGreaterThan(first.version());
        mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNMAPPED"))
            .andExpect(jsonPath("$.version").value(1));
    }
    @Test
    void twoDistinctNamesCanShareOneCanonicalAndRevertingOnePreservesOther() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Billing");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Invoice");
        var target=wiki.create(f.project(),f.actor(),"Target","Service: Revenue");
        String firstId=awaitService(f,first.id());
        String secondId=awaitService(f,second.id());
        String canonical=awaitService(f,target.id());
        long targetVersion=graph.entity(f.project(),UUID.fromString(canonical),f.actor()).source().version();
        for(String member:java.util.List.of(firstId,secondId)) {
            var body=Map.of("canonicalEntityId",canonical,"canonicalName","Revenue",
                "aliases",java.util.List.of(graph.entity(f.project(),UUID.fromString(member),f.actor()).displayName()),
                "metadata",Map.of("team","finance"),"confidence",0.75,"expectedVersion",0,
                "sourceVersion",graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version(),
                "canonicalSourceVersion",targetVersion);
            mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canonicalEntityId").value(canonical));
        }
        mvc.perform(delete(f.path()+"/resolution/decisions/"+firstId)
            .param("expectedVersion","1").with(f.token())).andExpect(status().isNoContent());
        mvc.perform(get(f.path()+"/resolution/decisions/"+secondId).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canonicalEntityId").value(canonical));
    }
    @Test
    void refreshedAnchorRequiresEachMemberToBeReconfirmed() throws Exception {
        var f=fixture();
        var a=wiki.create(f.project(),f.actor(),"Member A","Service: Billing");
        var b=wiki.create(f.project(),f.actor(),"Member B","Service: Invoice");
        var source=wiki.create(f.project(),f.actor(),"Anchor","Service: Revenue");
        String aId=awaitService(f,a.id()); String bId=awaitService(f,b.id());
        String anchorId=awaitService(f,source.id());
        long anchorVersion=graph.entity(f.project(),UUID.fromString(anchorId),f.actor()).source().version();
        for(String member:java.util.List.of(aId,bId)) {
            var body=Map.of("canonicalEntityId",anchorId,"canonicalName","Revenue",
                "aliases",java.util.List.of("Finance"),"metadata",Map.of("team","finance"),
                "confidence",0.8,"expectedVersion",0,
                "sourceVersion",graph.entity(f.project(),UUID.fromString(member),f.actor()).source().version(),
                "canonicalSourceVersion",anchorVersion);
            mvc.perform(put(f.path()+"/resolution/decisions/"+member).with(f.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        }
        wiki.update(f.project(),source.id(),f.actor(),"Anchor","Service: Revenue\nUpdated notes",source.version());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        long refreshed=-1;
        while(System.nanoTime()<deadline) {
            try { refreshed=graph.entity(f.project(),UUID.fromString(anchorId),f.actor()).source().version(); }
            catch(com.agentforge.core.shared.error.ResourceNotFoundException pending) { /* sync in progress */ }
            if(refreshed>anchorVersion) break;
            Thread.sleep(100);
        }
        assertThat(refreshed).isGreaterThan(anchorVersion);
        var confirm=Map.of("canonicalEntityId",anchorId,"canonicalName","Revenue",
            "aliases",java.util.List.of("Finance"),"metadata",Map.of("team","finance"),
            "confidence",0.8,"expectedVersion",1,
            "sourceVersion",graph.entity(f.project(),UUID.fromString(aId),f.actor()).source().version(),
            "canonicalSourceVersion",refreshed);
        mvc.perform(put(f.path()+"/resolution/decisions/"+aId).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(confirm)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(f.path()+"/resolution/canonicals/"+anchorId).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sourceVersion").value(refreshed));
        mvc.perform(get(f.path()+"/resolution/decisions/"+bId).with(f.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNMAPPED"));
    }
    @Test
    void canonicalAnchorsCannotBecomeMembersOrFormCycles() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Alpha");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Beta");
        String a=awaitService(f,first.id()); String b=awaitService(f,second.id());
        long aVersion=graph.entity(f.project(),UUID.fromString(a),f.actor()).source().version();
        long bVersion=graph.entity(f.project(),UUID.fromString(b),f.actor()).source().version();
        var self=Map.of("canonicalEntityId",a,"canonicalName","Alpha",
            "aliases",java.util.List.of("A"),"metadata",Map.of(),"confidence",0.8,
            "expectedVersion",0,"sourceVersion",aVersion,"canonicalSourceVersion",aVersion);
        mvc.perform(put(f.path()+"/resolution/decisions/"+a).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(self)))
            .andExpect(status().isBadRequest());
        var forward=Map.of("canonicalEntityId",b,"canonicalName","Beta",
            "aliases",java.util.List.of("A"),"metadata",Map.of(),"confidence",0.8,
            "expectedVersion",0,"sourceVersion",aVersion,"canonicalSourceVersion",bVersion);
        mvc.perform(put(f.path()+"/resolution/decisions/"+a).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(forward)))
            .andExpect(status().isOk());
        var reverse=Map.of("canonicalEntityId",a,"canonicalName","Alpha",
            "aliases",java.util.List.of("B"),"metadata",Map.of(),"confidence",0.8,
            "expectedVersion",0,"sourceVersion",bVersion,"canonicalSourceVersion",aVersion);
        mvc.perform(put(f.path()+"/resolution/decisions/"+b).with(f.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reverse)))
            .andExpect(status().isConflict());
    }

    @Test
    void concurrentOppositeMappingsSerializeEntityRolesWithoutPartialAudit() throws Exception {
        var f=fixture();
        var first=wiki.create(f.project(),f.actor(),"First","Service: Alpha");
        var second=wiki.create(f.project(),f.actor(),"Second","Service: Beta");
        UUID a=UUID.fromString(awaitService(f,first.id()));
        UUID b=UUID.fromString(awaitService(f,second.id()));
        long aVersion=graph.entity(f.project(),a,f.actor()).source().version();
        long bVersion=graph.entity(f.project(),b,f.actor()).source().version();
        var aToB=Map.of("canonicalEntityId",b,"canonicalName","Beta",
            "aliases",java.util.List.of("Alpha"),"metadata",Map.of(),"confidence",0.8,
            "expectedVersion",0,"sourceVersion",aVersion,"canonicalSourceVersion",bVersion);
        var bToA=Map.of("canonicalEntityId",a,"canonicalName","Alpha",
            "aliases",java.util.List.of("Beta"),"metadata",Map.of(),"confidence",0.8,
            "expectedVersion",0,"sourceVersion",bVersion,"canonicalSourceVersion",aVersion);
        String firstRole=java.util.stream.Stream.of(a,b).map(UUID::toString).sorted().findFirst().orElseThrow();
        String lockName=f.project()+":resolution-role:"+firstRole;

        try(var blocker=dataSource.getConnection();
            var lock=blocker.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?,0))")) {
            lock.setString(1,lockName);
            lock.execute();
            var start=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                Future<Integer> forward=executor.submit(() -> {
                    start.await();
                    return confirmStatus(f,a,aToB);
                });
                Future<Integer> reverse=executor.submit(() -> {
                    start.await();
                    return confirmStatus(f,b,bToA);
                });
                start.countDown();
                awaitBlockedAdvisoryLocks(2);
                try(var unlock=blocker.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,0))")) {
                    unlock.setString(1,lockName);
                    unlock.execute();
                }
                assertThat(java.util.List.of(forward.get(10,TimeUnit.SECONDS),reverse.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200,409);
            }
        }

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM graph_resolution_member
            WHERE project_id=? AND status='CONFIRMED'
            """,Integer.class,f.project())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM graph_resolution_event
            WHERE project_id=? AND action='CONFIRM'
            """,Integer.class,f.project())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM graph_canonical_entity c
            JOIN graph_resolution_member m ON m.project_id=c.project_id AND m.entity_id=c.id
            WHERE c.project_id=? AND m.status='CONFIRMED'
            """,Integer.class,f.project())).isZero();
    }

    private int confirmStatus(Fixture fixture,UUID member,Map<String,?> body) throws Exception {
        return mvc.perform(put(fixture.path()+"/resolution/decisions/"+member).with(fixture.token())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andReturn().getResponse().getStatus();
    }

    private void awaitBlockedAdvisoryLocks(int expected) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        int blocked=0;
        while(System.nanoTime()<deadline) {
            blocked=jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",
                Integer.class);
            if(blocked>=expected) return;
            Thread.sleep(50);
        }
        assertThat(blocked).as("confirmation transactions waiting on the shared entity-role lock")
            .isGreaterThanOrEqualTo(expected);
    }
}
