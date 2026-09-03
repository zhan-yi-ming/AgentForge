package com.agentforge.core.user.application;

import java.time.Instant;
import java.util.UUID;

import com.agentforge.core.user.domain.User;
import com.agentforge.core.user.UserRole;

public record UserView(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt,
        Instant updatedAt) {

    static UserView from(User user) {
        return new UserView(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
