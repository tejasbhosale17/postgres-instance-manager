package com.tejas.postgresinstancemanager.instances;

import java.time.Instant;
import java.util.UUID;

public record InstanceRecord(
        UUID id,
        UUID ownerId,
        String displayName,
        String postgresVersion,
        String state,
        String databaseName,
        String databaseUsername,
        Integer hostPort,
        Instant createdAt,
        Instant updatedAt) {
}
