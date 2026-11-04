package com.tejas.postgresinstancemanager.users;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String username,
        String role,
        boolean enabled,
        Instant createdAt) {
}
