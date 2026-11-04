package com.tejas.postgresinstancemanager.operations;

import java.time.Instant;
import java.util.UUID;

public record OperationRecord(
        UUID id,
        UUID ownerId,
        UUID instanceId,
        String operationType,
        String state,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt) {
}
