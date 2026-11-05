package com.tejas.postgresinstancemanager.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final String COLUMNS = "id, username, password_hash, role, enabled, created_at";
    private static final RowMapper<UserAccount> ROW_MAPPER = (resultSet, rowNumber) ->
            new UserAccount(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("username"),
                    resultSet.getString("password_hash"),
                    UserRole.valueOf(resultSet.getString("role")),
                    resultSet.getBoolean("enabled"),
                    resultSet.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE username = ?",
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                username);
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM app_user WHERE id = ?",
                resultSet -> resultSet.next() ? Optional.of(ROW_MAPPER.mapRow(resultSet, 0)) : Optional.empty(),
                id);
    }

    public List<UserSummary> findAllSummaries() {
        return jdbcTemplate.query(
                """
                SELECT id, username, role, enabled, created_at
                FROM app_user
                ORDER BY created_at, id
                """,
                (resultSet, rowNumber) -> new UserSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("username"),
                        UserRole.valueOf(resultSet.getString("role")).name(),
                        resultSet.getBoolean("enabled"),
                        resultSet.getTimestamp("created_at").toInstant()));
    }

    public UserAccount create(String username, String passwordHash, UserRole role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, ?, ?)",
                id,
                username,
                passwordHash,
                role.name());
        return findById(id).orElseThrow(() -> new IllegalStateException("Created user could not be loaded."));
    }

    public int countAdministrators() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE role = ? AND enabled = TRUE",
                Integer.class,
                UserRole.ADMINISTRATOR.name());
        return count == null ? 0 : count;
    }

    public void lockUsersForRoleChange() {
        jdbcTemplate.queryForList("SELECT id FROM app_user ORDER BY id FOR UPDATE", UUID.class);
    }

    public void updateRole(UUID userId, UserRole role) {
        jdbcTemplate.update("UPDATE app_user SET role = ? WHERE id = ?", role.name(), userId);
    }

    public void recordAuditEvent(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String detailsJson,
            String correlationId) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_event
                    (id, actor_id, action, resource_type, resource_id, details, correlation_id)
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
}
