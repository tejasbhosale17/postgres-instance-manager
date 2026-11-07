package com.tejas.postgresinstancemanager.backups;

public record BackupArtifactResult(long sizeBytes, String checksumSha256) {
}
