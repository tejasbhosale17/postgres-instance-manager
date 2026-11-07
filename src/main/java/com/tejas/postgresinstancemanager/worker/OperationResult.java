package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.backups.BackupArtifactResult;
import java.util.UUID;

public record OperationResult(
        String containerId,
        String volumeName,
        Integer hostPort,
        UUID backupId,
        Long backupSizeBytes,
        String backupChecksumSha256) {

    public static OperationResult empty() {
        return new OperationResult(null, null, null, null, null, null);
    }

    public static OperationResult provisioned(ProvisionedPostgres postgres) {
        return new OperationResult(
                postgres.containerId(), postgres.volumeName(), postgres.hostPort(), null, null, null);
    }

    public static OperationResult withHostPort(int hostPort) {
        return new OperationResult(null, null, hostPort, null, null, null);
    }

    public static OperationResult backup(UUID backupId, BackupArtifactResult backup) {
        return new OperationResult(
                null, null, null, backupId, backup.sizeBytes(), backup.checksumSha256());
    }
}
