package com.tejas.postgresinstancemanager.audit;

import java.time.Instant;
import java.util.UUID;

public record ActivityRecord(
        UUID id,
        String action,
        String resourceType,
        UUID resourceId,
        String details,
        String correlationId,
        Instant occurredAt) {
}
