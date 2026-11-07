package com.tejas.postgresinstancemanager.backups;

import com.tejas.postgresinstancemanager.worker.ManagedPostgresDocker;
import com.tejas.postgresinstancemanager.worker.PostgresProvisioningException;
import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Set;

@Service
public class BackupFileStore {

    private final Path rootDirectory;
    private final ManagedPostgresDocker managedPostgresDocker;

    public BackupFileStore(
            ManagedPostgresDocker managedPostgresDocker,
            @Value("${app.backup.directory:./backups}") String directory) {
        rootDirectory = Path.of(directory).toAbsolutePath().normalize();
        this.managedPostgresDocker = managedPostgresDocker;
    }

    public BackupArtifactResult create(
            UUID ownerId,
            UUID backupId,
            UUID instanceId,
            String databasePassword) {
        Path destination = artifactPath(ownerId, backupId);
        Path temporary = destination.resolveSibling(backupId + ".dump.partial");
        try {
            Files.createDirectories(destination.getParent());
            setOwnerOnlyPermissions(destination.getParent(), Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Files.deleteIfExists(temporary);
            createOwnerOnlyFile(temporary);
            managedPostgresDocker.createLogicalBackup(instanceId, databasePassword, temporary);
            long sizeBytes = Files.size(temporary);
            if (sizeBytes <= 0) {
                throw new PostgresProvisioningException("BACKUP_ARCHIVE_EMPTY");
            }
            String checksum = checksum(temporary);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return new BackupArtifactResult(sizeBytes, checksum);
        } catch (IOException exception) {
            cleanupPartial(temporary, exception);
            throw new PostgresProvisioningException("BACKUP_STORAGE_ERROR", exception);
        } catch (PostgresProvisioningException exception) {
            cleanupPartial(temporary, exception);
            throw exception;
        }
    }

    public void restore(BackupRecord backup, UUID targetInstanceId, String databasePassword, int hostPort) {
        Path artifact = artifactPath(backup.ownerId(), backup.id());
        try {
            if (!Files.isRegularFile(artifact)) {
                throw new PostgresProvisioningException("BACKUP_ARTIFACT_NOT_FOUND");
            }
            if (!MessageDigest.isEqual(
                    backup.checksumSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    checksum(artifact).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new PostgresProvisioningException("BACKUP_CHECKSUM_MISMATCH");
            }
            managedPostgresDocker.restoreLogicalBackup(
                    targetInstanceId, databasePassword, hostPort, artifact);
        } catch (IOException exception) {
            throw new PostgresProvisioningException("BACKUP_STORAGE_ERROR", exception);
        }
    }

    public Resource resource(BackupRecord backup) {
        Path artifact = artifactPath(backup.ownerId(), backup.id());
        try {
            if (!Files.isRegularFile(artifact)) {
                throw new ResourceNotFoundException();
            }
            if (!MessageDigest.isEqual(
                    backup.checksumSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    checksum(artifact).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new BackupArtifactUnavailableException();
            }
            return new FileSystemResource(artifact);
        } catch (IOException exception) {
            throw new PostgresProvisioningException("BACKUP_STORAGE_ERROR", exception);
        }
    }

    private Path artifactPath(UUID ownerId, UUID backupId) {
        Path artifact = rootDirectory.resolve(ownerId.toString()).resolve(backupId + ".dump").normalize();
        if (!artifact.startsWith(rootDirectory)) {
            throw new IllegalStateException("Backup artifact path escaped its configured directory.");
        }
        return artifact;
    }

    private String checksum(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private void cleanupPartial(Path path, Exception original) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private void createOwnerOnlyFile(Path path) throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.createFile(path, permissions);
        } catch (UnsupportedOperationException exception) {
            Files.createFile(path);
        }
    }

    private void setOwnerOnlyPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows applies the containing directory's inherited ACL.
        }
    }
}
