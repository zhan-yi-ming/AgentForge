package com.agentforge.core.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import com.agentforge.core.agent.application.AgentChatResult;

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

    @DynamicPropertySource
    static void agentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("agentforge.agent-service.base-url", () -> requiredEnvironment("AGENTFORGE_AGENT_SERVICE_URL"));
        registry.add("agentforge.agent-service.internal-token", () -> requiredEnvironment("AGENTFORGE_AGENT_INTERNAL_TOKEN"));
        registry.add("agentforge.agent-service.connect-timeout", () -> "PT2S");
        registry.add("agentforge.agent-service.read-timeout", () -> "PT15S");
        registry.add("spring.jackson.default-property-inclusion", () -> "non_null");
    }

    @Test
    void javaClientCallsRealPythonServiceOverHttp() {
        HttpAgentServiceClient client = new HttpAgentServiceClient(restClient);

        AgentChatResult result = client.chat(
                UUID.randomUUID(), UUID.randomUUID(), "  contract check  ", null, null);

        assertThat(result.answer()).isEqualTo("Agent service received: contract check");
        assertThat(result.conversationId()).isNotNull();
        assertThat(result.requestId()).isNotBlank();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when the contract test is enabled");
        }
        return value;
    }
}
