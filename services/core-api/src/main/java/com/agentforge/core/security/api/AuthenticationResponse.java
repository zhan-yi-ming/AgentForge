package com.agentforge.core.security.api;

import com.agentforge.core.security.application.AuthenticationResult;

public record AuthenticationResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthenticatedUserResponse user) {

    static AuthenticationResponse from(AuthenticationResult result) {
        return new AuthenticationResponse(
                result.token().value(),
                "Bearer",
                result.token().expiresInSeconds(),
                AuthenticatedUserResponse.from(result.user()));
    }
}
