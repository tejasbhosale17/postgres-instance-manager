package com.tejas.postgresinstancemanager.instances;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                WHERE id = ? AND state = 'PROVISIONING'
                """,
                instanceId);
    }
}
