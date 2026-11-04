package com.tejas.postgresinstancemanager.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public OperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OperationRecord> findByIdAndOwnerId(UUID operationId, UUID ownerId) {
        return jdbcTemplate.query(
                """
                SELECT id, owner_id, instance_id, operation_type, state, attempt_count, created_at, updated_at
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
                SELECT id, owner_id, instance_id, operation_type, state, attempt_count, created_at, updated_at
                FROM operation
                WHERE id = ?
                """,
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                operationId);
    }
}
