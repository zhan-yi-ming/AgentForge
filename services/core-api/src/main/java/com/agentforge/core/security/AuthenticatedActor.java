package com.agentforge.core.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import com.agentforge.core.shared.error.UnauthorizedException;

public record AuthenticatedActor(UUID userId, boolean admin) {

    public static AuthenticatedActor from(Jwt jwt) {
        try {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return new AuthenticatedActor(
                    UUID.fromString(jwt.getSubject()),
                    roles != null && roles.contains("ADMIN"));
        }
        catch (IllegalArgumentException exception) {
            throw new UnauthorizedException("The access token contains an invalid subject.");
        }
    }
}
