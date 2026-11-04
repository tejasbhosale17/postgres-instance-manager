package com.tejas.postgresinstancemanager.users;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String passwordHash,
        UserRole role,
        boolean enabled,
        Instant createdAt) {

    public UserSummary summary() {
        return new UserSummary(id, username, role.name(), enabled, createdAt);
    }
}
