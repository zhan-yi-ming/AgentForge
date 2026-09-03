package com.agentforge.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.agentforge.core.security.application.IssuedToken;
import com.agentforge.core.security.application.TokenService;
import com.agentforge.core.user.UserAccount;
import com.agentforge.core.user.UserRole;

class TokenServiceTest {

    @Test
    void issueCreatesSignedTokenWithRequiredIdentityClaims() {
        Instant now = Instant.now().minusSeconds(1);
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JwtProperties properties = new JwtProperties(
                secret,
                "https://agentforge.test/core-api",
                Duration.ofMinutes(30));
        SecurityConfiguration configuration = new SecurityConfiguration();
        SecretKey key = configuration.jwtSecretKey(properties);
        JwtEncoder encoder = configuration.jwtEncoder(key);
        JwtDecoder decoder = configuration.jwtDecoder(key, properties);
        TokenService service = new TokenService(encoder, properties, Clock.fixed(now, ZoneOffset.UTC));
        UserAccount user = new UserAccount(
                UUID.randomUUID(),
                "owner@example.com",
                "Owner",
                "{bcrypt}not-returned",
                UserRole.USER,
                now,
                now);

        IssuedToken issued = service.issue(user);
        Jwt decoded = decoder.decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo(user.id().toString());
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://agentforge.test/core-api");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(decoded.getExpiresAt()).isEqualTo(
                now.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(30)));
        assertThat(issued.expiresInSeconds()).isEqualTo(1800);
        assertThat(decoded.getClaims()).doesNotContainKeys("password", "passwordHash");
    }
}
