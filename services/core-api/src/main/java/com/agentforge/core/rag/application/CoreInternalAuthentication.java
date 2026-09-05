package com.agentforge.core.rag.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

import com.agentforge.core.rag.infrastructure.CoreInternalProperties;
import com.agentforge.core.shared.error.UnauthorizedException;

@Component
public class CoreInternalAuthentication {

    private final byte[] expectedToken;

    public CoreInternalAuthentication(CoreInternalProperties properties) {
        this.expectedToken = properties.token().getBytes(StandardCharsets.UTF_8);
    }

    public void requireValid(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            throw new UnauthorizedException("Invalid internal credentials.");
        }
    }
}
