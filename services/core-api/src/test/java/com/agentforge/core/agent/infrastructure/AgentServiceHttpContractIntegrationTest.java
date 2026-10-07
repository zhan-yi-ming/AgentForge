package com.agentforge.core.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;

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
import com.agentforge.core.agent.application.AgentStreamEvent;
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
    void javaClientConsumesRealPythonNdjsonStreamInOrder() {
        HttpAgentServiceClient client = new HttpAgentServiceClient(restClient, objectMapper);
        var events = new ArrayList<AgentStreamEvent>();

        client.stream(
                UUID.randomUUID(), UUID.randomUUID(), false,
                "create task: Stream workflow identity; priority=HIGH", null, null,
                events::add);

        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().type()).isEqualTo("metadata");
        assertThat(events).anyMatch(event -> "delta".equals(event.type()) && !event.text().isBlank());
        assertThat(events.getLast().type()).isEqualTo("complete");
        assertThat(events.getLast().toolProposal()).isNotNull();
        assertThat(events.getLast().toolProposal().actionWorkflowId()).isNotNull();
    }

    @Test
    void javaClientResumesTheCheckpointOwnerWhenTheDecisionActorIsAdmin() {
        HttpAgentServiceClient client = new HttpAgentServiceClient(restClient, objectMapper);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AgentChatResult chat = client.chat(
                projectId,
                userId,
                false,
                "create task: Resume contract; priority=HIGH",
                conversationId,
                "resume-contract-start");
        UUID workflowId = chat.toolProposal().actionWorkflowId();

        var resumed = client.resume(
                projectId,
                userId,
                true,
                conversationId,
                workflowId,
                actionId,
                "APPROVE",
                "resume-contract-key",
                "resume-contract-finish");

        assertThat(chat.toolProposal()).isNotNull();
        assertThat(workflowId).isNotNull();
        assertThat(resumed.conversationId()).isEqualTo(conversationId);
        assertThat(resumed.actionWorkflowId()).isEqualTo(workflowId);
        assertThat(resumed.actionId()).isEqualTo(actionId);
        assertThat(resumed.decision()).isEqualTo("APPROVE");
        assertThat(resumed.status()).isEqualTo("RESUMED");

        // The first resume response may be lost before Java executes the approved
        // action. A later chat round must not make that decision unrecoverable.
        AgentChatResult next = client.chat(projectId, userId, false,
                "create task: Next action while the first awaits execution", conversationId,
                "resume-contract-next");
        UUID nextWorkflowId = next.toolProposal().actionWorkflowId();
        var replayed = client.resume(projectId, userId, true, conversationId, workflowId,
                actionId, "APPROVE", "resume-contract-key", "resume-contract-retry");
        assertThat(replayed).isEqualTo(resumed);
        assertThatThrownBy(() -> client.resume(projectId, userId, true, conversationId, workflowId,
                actionId, "APPROVE", "wrong-key", "invalid-replay"))
                .isInstanceOf(com.agentforge.core.shared.error.ConflictException.class);
        UUID nextActionId = UUID.randomUUID();
        var completedNext = client.resume(projectId, userId, false, conversationId, nextWorkflowId,
                nextActionId, "REJECT", "next-key", "next-reject");
        assertThat(completedNext.actionWorkflowId()).isEqualTo(nextWorkflowId);
        assertThat(completedNext.actionId()).isEqualTo(nextActionId);
        assertThat(completedNext.decision()).isEqualTo("REJECT");
    }

    @Test
    void javaClientAbortsARealWaitingRoundAndAllowsTheNextRound() {
        HttpAgentServiceClient client = new HttpAgentServiceClient(restClient, objectMapper);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AgentChatResult waiting = client.chat(
                projectId,
                userId,
                false,
                "create task: Abort contract; priority=HIGH",
                conversationId,
                "abort-contract-start");
        UUID workflowId = waiting.toolProposal().actionWorkflowId();

        var aborted = client.abort(
                projectId,
                userId,
                false,
                conversationId,
                workflowId,
                "abort-contract-start");
        AgentChatResult next = client.chat(
                projectId,
                userId,
                false,
                "create task: Next contract round; priority=MEDIUM",
                conversationId,
                "abort-contract-next");

        assertThat(aborted.conversationId()).isEqualTo(conversationId);
        assertThat(aborted.actionWorkflowId()).isEqualTo(workflowId);
        assertThat(aborted.status()).isEqualTo("ABORTED");
        assertThat(aborted.requestId()).isEqualTo("abort-contract-start");
        assertThat(next.toolProposal()).isNotNull();
        assertThat(next.toolProposal().actionWorkflowId()).isNotEqualTo(workflowId);
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

    @Test
    void javaClientParsesStructuredToolProposal() {
        RestClient.Builder recordingBuilder = restClientBuilder.clone().baseUrl("http://contract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(recordingBuilder).build();
        UUID taskId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        server.expect(request -> { }).andRespond(withSuccess(
                "{\"conversationId\":\"15fd0b81-7cc8-4833-b5d9-79fb67784bc5\","
                        + "\"answer\":\"confirm\",\"requestId\":\"proposal-contract\","
                        + "\"toolProposal\":{\"actionType\":\"UPDATE_TASK\",\"taskId\":\"" + taskId + "\","
                        + "\"expectedVersion\":2,\"status\":\"DONE\",\"actionWorkflowId\":\""
                        + workflowId + "\"}}",
                MediaType.APPLICATION_JSON));

        AgentChatResult result = new HttpAgentServiceClient(recordingBuilder.build()).chat(
                UUID.randomUUID(), UUID.randomUUID(), false, "update", null, "proposal-contract");

        assertThat(result.toolProposal().taskId()).isEqualTo(taskId);
        assertThat(result.toolProposal().expectedVersion()).isEqualTo(2);
        assertThat(result.toolProposal().status()).isEqualTo("DONE");
        assertThat(result.toolProposal().actionWorkflowId()).isEqualTo(workflowId);
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
