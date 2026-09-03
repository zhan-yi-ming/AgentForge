package com.agentforge.core.security.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.user.UserAccount;
import com.agentforge.core.user.UserRole;

public record AuthenticatedUserResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt,
        Instant updatedAt) {

    static AuthenticatedUserResponse from(UserAccount user) {
        return new AuthenticatedUserResponse(
                user.id(),
                user.email(),
                user.displayName(),
                user.role(),
                user.createdAt(),
                user.updatedAt());
    }
}
