package com.agentforge.core.user.api;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.user.application.UserView;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {

    static UserResponse from(UserView user) {
        return new UserResponse(
                user.id(),
                user.email(),
                user.displayName(),
                user.createdAt(),
                user.updatedAt());
    }
}
