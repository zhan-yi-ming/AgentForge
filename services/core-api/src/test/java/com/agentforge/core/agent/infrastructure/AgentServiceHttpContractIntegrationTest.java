package com.agentforge.core.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.agentforge.core.agent.application.AgentChatResult;
import com.agentforge.core.shared.error.ServiceUnavailableException;

@EnabledIfEnvironmentVariable(named = "AGENTFORGE_AGENT_CONTRACT_TEST", matches = "true")
@SpringJUnitConfig(AgentServiceConfiguration.class)
@ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
class AgentServiceHttpContractIntegrationTest {

    @Autowired
    private RestClient restClient;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void agentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("agentforge.agent-service.base-url", () -> requiredEnvironment("AGENTFORGE_AGENT_SERVICE_URL"));
        registry.add("agentforge.agent-service.internal-token", () -> requiredEnvironment("AGENTFORGE_AGENT_INTERNAL_TOKEN"));
        registry.add("agentforge.agent-service.connect-timeout", () -> "PT2S");
        registry.add("agentforge.agent-service.read-timeout", () -> "PT30S");
        registry.add("spring.jackson.default-property-inclusion", () -> "non_null");
    }

    @Test
    void javaClientCallsRealPythonServiceOverHttp() {
        HttpAgentServiceClient client = new HttpAgentServiceClient(restClient);

        AgentChatResult result = client.chat(
                UUID.randomUUID(), UUID.randomUUID(), false, "  contract check  ", null, null);

        assertThat(result.answer()).startsWith("No relevant project context was found");
        assertThat(result.conversationId()).isNotNull();
        assertThat(result.requestId()).isNotBlank();
    }

    @Test
    void javaClientMapsInvalidInternalTokenToServiceUnavailable() {
        RestClient invalidTokenClient = restClient.mutate()
                .defaultHeaders(headers -> headers.set("X-AgentForge-Internal-Token", "invalid-test-token"))
                .build();

        assertThatThrownBy(() -> new HttpAgentServiceClient(invalidTokenClient).chat(
                UUID.randomUUID(), UUID.randomUUID(), false, "contract check", null, null))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void javaClientMapsUnavailableAgentServiceToServiceUnavailable() {
        AgentServiceProperties unavailableProperties = new AgentServiceProperties(
                URI.create("http://127.0.0.1:1"),
                requiredEnvironment("AGENTFORGE_AGENT_INTERNAL_TOKEN"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        RestClient unavailableClient = new AgentServiceConfiguration()
                .agentServiceRestClient(restClientBuilder, unavailableProperties);

        assertThatThrownBy(() -> new HttpAgentServiceClient(unavailableClient).chat(
                UUID.randomUUID(), UUID.randomUUID(), false, "contract check", null, null))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void bootBuilderOmitsNullConversationAndKeepsGeneratedRequestIdConsistent() {
        RestClient.Builder recordingBuilder = restClientBuilder.clone().baseUrl("http://contract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(recordingBuilder).build();
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/internal/v1/chat");
            assertThat(request).isInstanceOf(MockClientHttpRequest.class);
            JsonNode body = objectMapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.has("conversationId")).isFalse();
            assertThat(body.path("actorAdmin").asBoolean()).isFalse();
            String bodyRequestId = body.path("requestId").asText();
            assertThat(bodyRequestId).isNotBlank();
            assertThat(UUID.fromString(bodyRequestId)).isNotNull();
            assertThat(request.getHeaders().getFirst("X-Request-Id")).isEqualTo(bodyRequestId);
        }).andRespond(withSuccess(
                "{\"conversationId\":\"15fd0b81-7cc8-4833-b5d9-79fb67784bc5\","
                        + "\"answer\":\"contract response\",\"requestId\":\"outbound-contract\"}",
                MediaType.APPLICATION_JSON));

        AgentChatResult result = new HttpAgentServiceClient(recordingBuilder.build()).chat(
                UUID.randomUUID(), UUID.randomUUID(), false, "contract check", null, null);

        assertThat(result.answer()).isEqualTo("contract response");
        server.verify();
    }

    @Test
    void httpAdapterForwardsAdministratorFlag() {
        RestClient.Builder recordingBuilder = restClientBuilder.clone().baseUrl("http://contract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(recordingBuilder).build();
        server.expect(request -> {
            JsonNode body = objectMapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(body.path("actorAdmin").asBoolean()).isTrue();
        }).andRespond(withSuccess(
                "{\"conversationId\":\"15fd0b81-7cc8-4833-b5d9-79fb67784bc5\","
                        + "\"answer\":\"admin contract response\",\"requestId\":\"admin-contract\"}",
                MediaType.APPLICATION_JSON));

        AgentChatResult result = new HttpAgentServiceClient(recordingBuilder.build()).chat(
                UUID.randomUUID(), UUID.randomUUID(), true, "contract check", null, "admin-contract");

        assertThat(result.answer()).isEqualTo("admin contract response");
        server.verify();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when the contract test is enabled");
        }
        return value;
    }
}
