package com.agentforge.core.security;

import java.time.Duration;
import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agentforge.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        @NotNull Duration ttl) {

    public JwtProperties {
        if (issuer != null) {
            URI parsedIssuer = URI.create(issuer);
            if (!parsedIssuer.isAbsolute()) {
                throw new IllegalArgumentException("JWT issuer must be an absolute URI.");
            }
        }
        if (ttl != null && (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(24)) > 0)) {
            throw new IllegalArgumentException("JWT ttl must be greater than zero and no more than 24 hours.");
        }
    }
}
