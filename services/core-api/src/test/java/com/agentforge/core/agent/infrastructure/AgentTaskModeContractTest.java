package com.agentforge.core.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.agentforge.core.agent.application.AgentTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;

class AgentTaskModeContractTest {
    @Test
    void forwardsExplicitModeOverBothChatContracts() {
        var builder = RestClient.builder().baseUrl("http://localhost");
        var server = MockRestServiceServer.bindTo(builder).build();
        var mapper = new ObjectMapper();
        server.expect(request -> assertThat(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()).path("taskType").asText()).isEqualTo("FORMAT"))
                .andRespond(withSuccess("{\"conversationId\":\"" + UUID.randomUUID() + "\",\"answer\":\"formatted\",\"requestId\":\"test\",\"sources\":[]}", MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()).path("taskType").asText()).isEqualTo("REVIEW"))
                .andRespond(withSuccess("{\"type\":\"complete\",\"sources\":[]}\n", MediaType.APPLICATION_NDJSON));
        var client = new HttpAgentServiceClient(builder.build());
        assertThat(client.chat(UUID.randomUUID(), UUID.randomUUID(), false, "notes", null, "test", AgentTaskType.FORMAT).answer()).isEqualTo("formatted");
        var events = new java.util.ArrayList<com.agentforge.core.agent.application.AgentStreamEvent>();
        client.stream(UUID.randomUUID(), UUID.randomUUID(), false, "notes", null, "test", AgentTaskType.REVIEW, events::add);
        assertThat(events).hasSize(1);
        server.verify();
    }
    @Test
    void configuredReadBudgetAllowsDelayedPlanningBeforeResponse() throws Exception {
        String config = new org.springframework.core.io.ClassPathResource("application.yml")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("AGENTFORGE_AGENT_READ_TIMEOUT:PT(\\d+)S").matcher(config);
        assertThat(matcher.find()).isTrue();
        // Scale seconds to 10 ms: the former 75 s budget expires before this 120 s planning delay.
        var readBudget = java.time.Duration.ofMillis(Long.parseLong(matcher.group(1)) * 10);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/chat", exchange -> {
            try { Thread.sleep(1200); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] body = ("{\"conversationId\":\"" + UUID.randomUUID() + "\",\"answer\":\"recovered\",\"requestId\":\"test\",\"sources\":[]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var properties = new AgentServiceProperties(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-only-internal-token", java.time.Duration.ofSeconds(1), readBudget);
            var client = new HttpAgentServiceClient(new AgentServiceConfiguration().agentServiceRestClient(RestClient.builder(), properties));
            assertThat(client.chat(UUID.randomUUID(), UUID.randomUUID(), false, "notes", null, "test").answer()).isEqualTo("recovered");
        } finally { server.stop(0); }
    }
}