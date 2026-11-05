package com.tejas.postgresinstancemanager.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String detailsJson,
            String correlationId) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_event (id, actor_id, action, resource_type, resource_id, details, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                actorId,
                action,
                resourceType,
                resourceId,
                detailsJson,
                correlationId);
    }

    public List<ActivityRecord> findActivity(UUID ownerId, boolean administrator, int limit) {
        return jdbcTemplate.query(
                """
                SELECT e.id, e.action, e.resource_type, e.resource_id, e.details::text AS details,
                       e.correlation_id, e.occurred_at
                FROM audit_event e
                WHERE ? = TRUE
                   OR e.actor_id = ?
                   OR (e.resource_type = 'USER' AND e.resource_id = ?)
                   OR EXISTS (
                       SELECT 1
                       FROM operation o
                       WHERE o.id = e.resource_id
                         AND o.owner_id = ?
                         AND e.resource_type = 'OPERATION'
                   )
                ORDER BY e.occurred_at DESC, e.id DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new ActivityRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("action"),
                        resultSet.getString("resource_type"),
                        resultSet.getObject("resource_id", UUID.class),
                        resultSet.getString("details"),
                        resultSet.getString("correlation_id"),
                        resultSet.getTimestamp("occurred_at").toInstant()),
                administrator,
                ownerId,
                ownerId,
                ownerId,
                limit);
    }
}
