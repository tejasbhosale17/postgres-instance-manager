package com.tejas.postgresinstancemanager.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
            String credentialCiphertext,
            String idempotencyKey,
            String requestHash,
            String correlationId) {
        jdbcTemplate.update(
                """
                INSERT INTO operation
                    (id, owner_id, requester_id, instance_id, operation_type, state, payload,
                     idempotency_key, request_hash, correlation_id)
                VALUES (?, ?, ?, ?, 'CREATE_INSTANCE', 'QUEUED',
                        jsonb_build_object('credentialCiphertext', ?), ?, ?, ?)
                """,
                operationId,
                ownerId,
                ownerId,
                instanceId,
                credentialCiphertext,
                idempotencyKey,
                requestHash,
                correlationId);
    }

    public void enqueueInstanceOperation(
            UUID operationId,
            UUID ownerId,
            UUID requesterId,
            UUID instanceId,
            String operationType,
            String credentialCiphertext,
            UUID backupId,
            String previousInstanceState,
            String idempotencyKey,
            String requestHash,
            String correlationId) {
        jdbcTemplate.update(
                """
                INSERT INTO operation
                    (id, owner_id, requester_id, instance_id, operation_type, state, payload,
                     previous_instance_state, idempotency_key, request_hash, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'QUEUED',
                        jsonb_strip_nulls(jsonb_build_object(
                            'credentialCiphertext', CAST(? AS VARCHAR), 'backupId', CAST(? AS VARCHAR))),
                        ?, ?, ?, ?)
                """,
                operationId,
                ownerId,
                requesterId,
                instanceId,
                operationType,
                credentialCiphertext,
                backupId,
                previousInstanceState,
                idempotencyKey,
                requestHash,
                correlationId);
    }

    public void lockIdempotencyKey(UUID ownerId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> {
                    while (resultSet.next()) {
                        // Consume the lock result within the transaction.
                    }
                    return null;
                },
                ownerId + ":" + idempotencyKey);
    }

    public Optional<IdempotentOperation> findIdempotent(UUID ownerId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT id, state, request_hash
                FROM operation
                WHERE requester_id = ? AND idempotency_key = ?
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new IdempotentOperation(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("state"),
                                resultSet.getString("request_hash").trim()))
                        : Optional.empty(),
                ownerId,
                idempotencyKey);
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
    public Optional<OperationJob> claimNext(String workerId, Duration leaseDuration) {
        return jdbcTemplate.query(
                """
                UPDATE operation
                SET state = 'RUNNING', attempt_count = attempt_count + 1,
                    worker_id = ?, lease_token = gen_random_uuid(),
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    next_attempt_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = (
                    SELECT id
                    FROM operation
                    WHERE (state = 'QUEUED'
                           OR (state = 'RETRY_WAIT' AND next_attempt_at <= CURRENT_TIMESTAMP))
                      AND attempt_count < max_attempts
                      AND operation_type IN ('CREATE_INSTANCE', 'START_INSTANCE', 'STOP_INSTANCE',
                                             'RESTART_INSTANCE', 'DELETE_INSTANCE', 'BACKUP', 'RESTORE')
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING id, owner_id, instance_id, operation_type, previous_instance_state,
                          lease_token, attempt_count, max_attempts,
                          payload ->> 'credentialCiphertext' AS credential_ciphertext,
                          correlation_id,
                          NULLIF(payload ->> 'backupId', '')::UUID AS backup_id
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new OperationJob(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getObject("owner_id", UUID.class),
                                resultSet.getObject("instance_id", UUID.class),
                                resultSet.getString("operation_type"),
                                resultSet.getString("credential_ciphertext"),
                                resultSet.getString("previous_instance_state"),
                                resultSet.getObject("lease_token", UUID.class),
                                resultSet.getInt("attempt_count"),
                                resultSet.getInt("max_attempts"),
                                resultSet.getString("correlation_id"),
                                resultSet.getObject("backup_id", UUID.class)))
                        : Optional.empty(),
                workerId,
                leaseDuration.toSeconds());
    }

    public boolean renewLease(OperationJob operation, String workerId, Duration leaseDuration) {
        return jdbcTemplate.update(
                """
                UPDATE operation
                SET lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'RUNNING' AND worker_id = ?
                  AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP
                """,
                leaseDuration.toSeconds(),
                operation.id(),
                workerId,
                operation.leaseToken()) == 1;
    }

    public boolean markSucceeded(OperationJob operation, String workerId) {
        return jdbcTemplate.update(
                """
                UPDATE operation
                SET state = 'SUCCEEDED', error_code = NULL, worker_id = NULL,
                    lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'RUNNING' AND worker_id = ?
                  AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP
                """,
                operation.id(),
                workerId,
                operation.leaseToken()) == 1;
    }

    public Optional<Boolean> markRetryOrFailed(OperationJob operation, String workerId, String errorCode) {
        List<String> states = jdbcTemplate.query(
                """
                UPDATE operation
                SET state = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    next_attempt_at = CASE WHEN attempt_count >= max_attempts THEN NULL
                        ELSE CURRENT_TIMESTAMP
                            + LEAST(300, 5 * power(2, GREATEST(attempt_count - 1, 0))) * INTERVAL '1 second'
                        END,
                    error_code = ?, worker_id = NULL, lease_token = NULL, lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'RUNNING' AND worker_id = ?
                  AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP
                RETURNING state
                """,
                (resultSet, rowNumber) -> resultSet.getString("state"),
                errorCode,
                operation.id(),
                workerId,
                operation.leaseToken());
        return states.stream().findFirst().map("FAILED"::equals);
    }

    @Transactional
    public List<RecoveredOperation> recoverExpiredLeases() {
        return jdbcTemplate.query(
                """
                WITH expired AS (
                    SELECT id
                    FROM operation
                    WHERE state = 'RUNNING'
                      AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE operation o
                SET state = CASE WHEN o.attempt_count >= o.max_attempts THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    next_attempt_at = CASE WHEN o.attempt_count >= o.max_attempts THEN NULL
                        ELSE CURRENT_TIMESTAMP
                            + LEAST(300, 5 * power(2, GREATEST(o.attempt_count - 1, 0))) * INTERVAL '1 second'
                        END,
                    error_code = 'WORKER_LEASE_EXPIRED', worker_id = NULL,
                    lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                FROM expired
                WHERE o.id = expired.id
                RETURNING o.id, o.instance_id, o.operation_type,
                          o.previous_instance_state, o.state, o.correlation_id,
                          NULLIF(o.payload ->> 'backupId', '')::UUID AS backup_id
                """,
                (resultSet, rowNumber) -> new RecoveredOperation(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("instance_id", UUID.class),
                        resultSet.getString("operation_type"),
                        resultSet.getString("previous_instance_state"),
                        resultSet.getString("state"),
                        resultSet.getString("correlation_id"),
                        resultSet.getObject("backup_id", UUID.class)));
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

    public long countByState(String state) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE state = ?",
                Long.class,
                state);
        return count == null ? 0 : count;
    }
}
