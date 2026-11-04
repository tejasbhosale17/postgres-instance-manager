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
            "id, owner_id, display_name, postgres_version, state, created_at, updated_at";
    private static final RowMapper<InstanceRecord> ROW_MAPPER = (resultSet, rowNumber) ->
            new InstanceRecord(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getString("display_name"),
                    resultSet.getString("postgres_version"),
                    resultSet.getString("state"),
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
}
