package com.agentforge.core.agent.infrastructure;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agentforge.agent-service")
public record AgentServiceProperties(
        @NotNull URI baseUrl,
        @NotBlank @Size(min = 16) String internalToken,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
