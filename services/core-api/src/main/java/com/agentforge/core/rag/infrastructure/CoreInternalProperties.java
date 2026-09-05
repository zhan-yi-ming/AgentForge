package com.agentforge.core.rag.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agentforge.core-internal")
public record CoreInternalProperties(@NotBlank @Size(min = 16) String token) {
}
