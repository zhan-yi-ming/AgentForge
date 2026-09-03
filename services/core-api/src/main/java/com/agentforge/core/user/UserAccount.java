package com.agentforge.core.user;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String email,
        String displayName,
        String passwordHash,
        UserRole role,
        Instant createdAt,
        Instant updatedAt) {
}
