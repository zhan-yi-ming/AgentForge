package com.agentforge.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

class JwtSecretKeyValidationTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void invalidBase64SecretFailsFast() {
        assertThatThrownBy(() -> configuration.jwtSecretKey(properties("!!!not-base64!!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    void secretShorterThan32BytesFailsFast() {
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> configuration.jwtSecretKey(properties(secret)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void valid32ByteSecretProducesHmacSha256Key() {
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        SecretKey key = configuration.jwtSecretKey(properties(secret));

        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSize(32);
    }

    private JwtProperties properties(String secret) {
        return new JwtProperties(secret, "https://agentforge.test/core-api", Duration.ofMinutes(30));
    }
}
