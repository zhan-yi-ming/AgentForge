package com.agentforge.core.user.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.user.domain.User;

public record UserView(
        UUID id,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {

    static UserView from(User user) {
        return new UserView(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
