package com.tejas.postgresinstancemanager.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OperationRepository {

    private static final RowMapper<OperationRecord> ROW_MAPPER = (resultSet, rowNumber) ->
            new OperationRecord(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getObject("instance_id", UUID.class),
                    resultSet.getString("operation_type"),
                    resultSet.getString("state"),
                    resultSet.getInt("attempt_count"),
                    resultSet.getString("error_code"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public OperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enqueueProvisioning(
            UUID operationId,
            UUID ownerId,
            UUID instanceId,
            String credentialCiphertext) {
        jdbcTemplate.update(
                """
                INSERT INTO operation (id, owner_id, instance_id, operation_type, state, payload)
                VALUES (?, ?, ?, 'CREATE_INSTANCE', 'QUEUED',
                        jsonb_build_object('credentialCiphertext', ?))
                """,
                operationId,
                ownerId,
                instanceId,
                credentialCiphertext);
    }

    public void enqueueInstanceOperation(
            UUID operationId,
            UUID ownerId,
            UUID instanceId,
            String operationType,
            String credentialCiphertext) {
        jdbcTemplate.update(
                """
                INSERT INTO operation (id, owner_id, instance_id, operation_type, state, payload)
                VALUES (?, ?, ?, ?, 'QUEUED',
                        CASE WHEN CAST(? AS VARCHAR) IS NULL THEN '{}'::JSONB
                             ELSE jsonb_build_object('credentialCiphertext', CAST(? AS VARCHAR)) END)
                """,
                operationId,
                ownerId,
                instanceId,
                operationType,
                credentialCiphertext,
                credentialCiphertext);
    }

    public Optional<String> findProvisioningCredential(UUID instanceId) {
        return jdbcTemplate.query(
                """
                SELECT payload ->> 'credentialCiphertext'
                FROM operation
                WHERE instance_id = ? AND operation_type = 'CREATE_INSTANCE'
                  AND state = 'SUCCEEDED'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                resultSet -> resultSet.next()
                        ? Optional.ofNullable(resultSet.getString(1))
                        : Optional.empty(),
                instanceId);
    }

    @Transactional
    public Optional<OperationJob> claimNext() {
        List<OperationJob> jobs = jdbcTemplate.query(
                """
                UPDATE operation
                SET state = 'RUNNING', attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT id
                    FROM operation
                    WHERE state = 'QUEUED'
                      AND operation_type IN ('CREATE_INSTANCE', 'START_INSTANCE', 'STOP_INSTANCE',
                                             'RESTART_INSTANCE', 'DELETE_INSTANCE')
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING id, instance_id, operation_type,
                          payload ->> 'credentialCiphertext' AS credential_ciphertext
                """,
                (resultSet, rowNumber) -> new OperationJob(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("instance_id", UUID.class),
                        resultSet.getString("operation_type"),
                        resultSet.getString("credential_ciphertext")));
        return jobs.stream().findFirst();
    }

    public void markSucceeded(UUID operationId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE operation
                SET state = 'SUCCEEDED', error_code = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'RUNNING'
                """,
                operationId);
        if (updated != 1) {
            throw new IllegalStateException("The operation could not be marked successful.");
        }
    }

    public void markFailed(UUID operationId, String errorCode) {
        int updated = jdbcTemplate.update(
                """
                UPDATE operation
                SET state = 'FAILED', error_code = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'RUNNING'
                """,
                errorCode,
                operationId);
        if (updated != 1) {
            throw new IllegalStateException("The operation could not be marked failed.");
        }
    }

    public Optional<OperationRecord> findByIdAndOwnerId(UUID operationId, UUID ownerId) {
        return jdbcTemplate.query(
                """
                SELECT id, owner_id, instance_id, operation_type, state, attempt_count, error_code,
                       created_at, updated_at
                FROM operation
                WHERE id = ? AND owner_id = ?
                """,
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                operationId,
                ownerId);
    }

    public Optional<OperationRecord> findById(UUID operationId) {
        return jdbcTemplate.query(
                """
                SELECT id, owner_id, instance_id, operation_type, state, attempt_count, error_code,
                       created_at, updated_at
                FROM operation
                WHERE id = ?
                """,
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                operationId);
    }
}
