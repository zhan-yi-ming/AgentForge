package com.agentforge.core.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.agentforge.core.shared.error.UnauthorizedException;

class AuthenticatedActorTest {

    @Test
    void missingSubjectIsUnauthorizedInsteadOfServerError() {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "HS256"),
                Map.of("roles", java.util.List.of("USER")));

        assertThatThrownBy(() -> AuthenticatedActor.from(jwt))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("missing a subject");
    }
}
