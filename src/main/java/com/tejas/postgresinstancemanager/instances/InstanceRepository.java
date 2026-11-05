package com.tejas.postgresinstancemanager.instances;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InstanceRepository {

    private static final String COLUMNS =
            """
            id, owner_id, display_name, postgres_version, state, database_name,
            database_username, host_port, created_at, updated_at
            """;
    private static final RowMapper<InstanceRecord> ROW_MAPPER = (resultSet, rowNumber) ->
            new InstanceRecord(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getString("display_name"),
                    resultSet.getString("postgres_version"),
                    resultSet.getString("state"),
                    resultSet.getString("database_name"),
                    resultSet.getString("database_username"),
                    (Integer) resultSet.getObject("host_port"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public InstanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InstanceRecord> findByOwnerId(UUID ownerId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM managed_instance WHERE owner_id = ? ORDER BY created_at DESC, id",
                ROW_MAPPER,
                ownerId);
    }

    public List<InstanceRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM managed_instance ORDER BY created_at DESC, id",
                ROW_MAPPER);
    }

    public List<InstanceRecord> findStableInstancesForReconciliation() {
        return jdbcTemplate.query(
                """
                SELECT %s
                FROM managed_instance i
                WHERE state IN ('READY', 'STOPPED')
                  AND NOT EXISTS (
                      SELECT 1 FROM operation o
                      WHERE o.instance_id = i.id
                        AND o.state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                  )
                ORDER BY updated_at, id
                  """.formatted(COLUMNS),
                ROW_MAPPER);
    }

    public Optional<InstanceRecord> findByIdAndOwnerId(UUID instanceId, UUID ownerId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM managed_instance WHERE id = ? AND owner_id = ?",
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                instanceId,
                ownerId);
    }

    public Optional<InstanceRecord> findById(UUID instanceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM managed_instance WHERE id = ?",
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                instanceId);
    }

    public void create(UUID instanceId, UUID ownerId, String displayName, String postgresVersion) {
        jdbcTemplate.update(
                """
                INSERT INTO managed_instance
                    (id, owner_id, display_name, postgres_version, state)
                VALUES (?, ?, ?, ?, 'PROVISIONING')
                """,
                instanceId,
                ownerId,
                displayName,
                postgresVersion);
    }

    public void markReady(UUID instanceId, String containerId, String volumeName, int hostPort) {
        int updated = jdbcTemplate.update(
                """
                UPDATE managed_instance
                SET state = 'READY', container_id = ?, volume_name = ?, host_port = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'PROVISIONING'
                """,
                containerId,
                volumeName,
                hostPort,
                instanceId);
        if (updated != 1) {
            throw new IllegalStateException("The instance could not be marked ready.");
        }
    }

    public void markFailed(UUID instanceId) {
        jdbcTemplate.update(
                """
                UPDATE managed_instance
                SET state = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state IN
                    ('PROVISIONING', 'STARTING', 'STOPPING', 'RESTARTING', 'DELETING')
                """,
                instanceId);
    }

    @Transactional
    public String beginOperation(
            UUID instanceId,
            UUID ownerId,
            boolean administrator,
            List<String> allowedStates,
            String nextState) {
        List<String> currentStates = jdbcTemplate.query(
                """
                SELECT state FROM managed_instance
                WHERE id = ? AND (? OR owner_id = ?)
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getString("state"),
                instanceId,
                administrator,
                ownerId);
        if (currentStates.isEmpty()) {
            throw new com.tejas.postgresinstancemanager.api.ResourceNotFoundException();
        }
        String currentState = currentStates.get(0);
        if (!allowedStates.contains(currentState)) {
            throw new InstanceStateConflictException();
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE managed_instance
                SET state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = ?
                """,
                nextState,
                instanceId,
                currentState);
        if (updated != 1) {
            throw new IllegalStateException("The instance state changed while queuing its operation.");
        }
        return currentState;
    }

    public void restoreState(UUID instanceId, String previousState) {
        if (previousState == null) {
            markFailed(instanceId);
            return;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE managed_instance
                SET state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state IN
                    ('PROVISIONING', 'STARTING', 'STOPPING', 'RESTARTING', 'DELETING')
                """,
                previousState,
                instanceId);
        requireLifecycleUpdate(updated);
    }

    public void reconcileState(UUID instanceId, String state, int hostPort) {
        jdbcTemplate.update(
                """
                UPDATE managed_instance i
                SET state = ?, host_port = ?, updated_at = CURRENT_TIMESTAMP
                WHERE i.id = ? AND i.state IN ('READY', 'STOPPED')
                  AND NOT EXISTS (
                      SELECT 1 FROM operation o
                      WHERE o.instance_id = i.id
                        AND o.state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                  )
                """,
                state,
                hostPort,
                instanceId);
    }

    public void markMissingManagedContainer(UUID instanceId) {
        jdbcTemplate.update(
                """
                UPDATE managed_instance i
                SET state = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE i.id = ? AND i.state IN ('READY', 'STOPPED')
                  AND NOT EXISTS (
                      SELECT 1 FROM operation o
                      WHERE o.instance_id = i.id
                        AND o.state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
                  )
                """,
                instanceId);
    }

    public void markReadyAfterLifecycle(UUID instanceId, int hostPort) {
        updateLifecycleState(instanceId, "READY", hostPort);
    }

    public void markStopped(UUID instanceId) {
        updateLifecycleState(instanceId, "STOPPED", null);
    }

    public void markDeleted(UUID instanceId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE managed_instance
                SET state = 'DELETED', container_id = NULL, volume_name = NULL, host_port = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'DELETING'
                """,
                instanceId);
        requireLifecycleUpdate(updated);
    }

    private void updateLifecycleState(UUID instanceId, String state, Integer hostPort) {
        int updated = jdbcTemplate.update(
                """
                UPDATE managed_instance
                SET state = ?, host_port = COALESCE(?, host_port), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state IN ('STARTING', 'STOPPING', 'RESTARTING')
                """,
                state,
                hostPort,
                instanceId);
        requireLifecycleUpdate(updated);
    }

    private void requireLifecycleUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("The instance lifecycle state could not be updated.");
        }
    }
}
