package com.tejas.postgresinstancemanager.backups;

import java.time.Instant;
import java.util.UUID;

public record BackupRecord(
        UUID id,
        UUID ownerId,
        UUID sourceInstanceId,
        UUID operationId,
        String state,
        Long sizeBytes,
        String checksumSha256,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {
}
