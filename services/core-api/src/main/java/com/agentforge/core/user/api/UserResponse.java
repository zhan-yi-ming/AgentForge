package com.agentforge.core.user.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.user.application.UserView;
import com.agentforge.core.user.UserRole;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt,
        Instant updatedAt) {

    static UserResponse from(UserView user) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.displayName(),
                user.role(),
                user.createdAt(),
                user.updatedAt());
    }
}
