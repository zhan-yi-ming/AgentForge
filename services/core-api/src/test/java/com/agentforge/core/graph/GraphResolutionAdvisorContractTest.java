package com.agentforge.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentforge.core.graph.application.GraphResolutionAdvisor;

class GraphResolutionAdvisorContractTest {
    @Test void forwardsBoundedCandidatesAndAcceptsOnlyAllowlistedRecommendation() throws Exception {
        var builder=RestClient.builder().baseUrl("http://localhost");
        var server=MockRestServiceServer.bindTo(builder).build();
        var source=UUID.randomUUID(); var candidate=UUID.randomUUID();
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/internal/v1/graph/resolution/suggest");
            var body=new ObjectMapper().readTree(((MockClientHttpRequest)request).getBodyAsString());
            assertThat(body.path("entityId").asText()).isEqualTo(source.toString());
            assertThat(body.path("candidates").size()).isEqualTo(1);
            assertThat(body.path("candidates").get(0).path("entityId").asText()).isEqualTo(candidate.toString());
        }).andRespond(withSuccess("{\"recommendedCandidateId\":\""+candidate+"\",\"confidence\":0.94,\"reviewRequired\":true}",MediaType.APPLICATION_JSON));
        var advisor=new GraphResolutionAdvisor(builder.build());
        var suggestion=advisor.suggest(source,"Billing","SERVICE",List.of(new GraphResolutionAdvisor.Candidate(candidate,"Billing")));
        assertThat(suggestion.recommendedCandidateId()).isEqualTo(candidate);
        assertThat(suggestion.confidence()).isEqualTo(0.94);
        server.verify();
    }
    @Test void rejectsUnknownModelIdAndDegradesOnDependencyFailure() {
        var builder=RestClient.builder().baseUrl("http://localhost");
        var server=MockRestServiceServer.bindTo(builder).build();
        var source=UUID.randomUUID(); var candidate=UUID.randomUUID();
        server.expect(request -> {}).andRespond(withSuccess("{\"recommendedCandidateId\":\""+UUID.randomUUID()+"\",\"confidence\":1,\"reviewRequired\":true}",MediaType.APPLICATION_JSON));
        server.expect(request -> {}).andRespond(withServerError());
        var advisor=new GraphResolutionAdvisor(builder.build());
        var options=List.of(new GraphResolutionAdvisor.Candidate(candidate,"Billing"));
        assertThat(advisor.suggest(source,"Billing","SERVICE",options).recommendedCandidateId()).isNull();
        assertThat(advisor.suggest(source,"Billing","SERVICE",options).recommendedCandidateId()).isNull();
        server.verify();
    }
    @Test void livePythonProcessAcceptsJavaContract() {
        String base=System.getenv("AGENTFORGE_RESOLUTION_SMOKE_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(base!=null && !base.isBlank());
        var source=UUID.randomUUID(); var candidate=UUID.randomUUID();
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build());
        var client=RestClient.builder().baseUrl(base).requestFactory(factory)
            .defaultHeader("X-AgentForge-Internal-Token","test-only-internal-token").build();
        var advice=new GraphResolutionAdvisor(client).suggest(source,"Billing Service","SERVICE",
            List.of(new GraphResolutionAdvisor.Candidate(candidate,"Billing Service")));
        assertThat(advice.recommendedCandidateId()).isEqualTo(candidate);
        assertThat(advice.confidence()).isEqualTo(1);
    }
}
