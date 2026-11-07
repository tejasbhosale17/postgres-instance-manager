package com.tejas.postgresinstancemanager.backups;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BackupRepository {

    private static final String COLUMNS =
            """
            id, owner_id, source_instance_id, operation_id, state, size_bytes,
            checksum_sha256, error_code, created_at, updated_at
            """;
    private static final org.springframework.jdbc.core.RowMapper<BackupRecord> ROW_MAPPER =
            (resultSet, rowNumber) -> new BackupRecord(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getObject("source_instance_id", UUID.class),
                    resultSet.getObject("operation_id", UUID.class),
                    resultSet.getString("state"),
                    (Long) resultSet.getObject("size_bytes"),
                    resultSet.getString("checksum_sha256"),
                    resultSet.getString("error_code"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public BackupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createPending(UUID backupId, UUID ownerId, UUID instanceId, UUID operationId) {
        jdbcTemplate.update(
                """
                INSERT INTO backup_artifact (id, owner_id, source_instance_id, operation_id, state)
                VALUES (?, ?, ?, ?, 'PENDING')
                """,
                backupId,
                ownerId,
                instanceId,
                operationId);
    }

    public void markReady(UUID backupId, long sizeBytes, String checksumSha256) {
        requireUpdate(jdbcTemplate.update(
                """
                UPDATE backup_artifact
                SET state = 'READY', size_bytes = ?, checksum_sha256 = ?, error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'PENDING'
                """,
                sizeBytes,
                checksumSha256,
                backupId));
    }

    public void markFailed(UUID backupId, String errorCode) {
        requireUpdate(jdbcTemplate.update(
                """
                UPDATE backup_artifact
                SET state = 'FAILED', size_bytes = NULL, checksum_sha256 = NULL, error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'PENDING'
                """,
                errorCode,
                backupId));
    }

    public Optional<BackupRecord> findByIdAndOwnerId(UUID backupId, UUID ownerId) {
        return find("SELECT " + COLUMNS + " FROM backup_artifact WHERE id = ? AND owner_id = ?",
                backupId, ownerId);
    }

    public Optional<BackupRecord> findById(UUID backupId) {
        return find("SELECT " + COLUMNS + " FROM backup_artifact WHERE id = ?", backupId);
    }

    public List<BackupRecord> findByOwnerId(UUID ownerId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM backup_artifact WHERE owner_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER,
                ownerId,
                limit);
    }

    public List<BackupRecord> findAll(int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM backup_artifact ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER,
                limit);
    }

    public List<BackupRecord> findBySourceInstanceIdAndOwnerId(UUID instanceId, UUID ownerId) {
        return jdbcTemplate.query(
                """
                SELECT %s FROM backup_artifact
                WHERE source_instance_id = ? AND owner_id = ?
                ORDER BY created_at DESC, id DESC
                """.formatted(COLUMNS),
                ROW_MAPPER,
                instanceId,
                ownerId);
    }

    private Optional<BackupRecord> find(String sql, Object... parameters) {
        return jdbcTemplate.query(
                sql,
                resultSet -> resultSet.next()
                        ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0))
                        : Optional.empty(),
                parameters);
    }

    private void requireUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("The backup artifact state could not be updated.");
        }
    }
}
