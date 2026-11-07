package com.tejas.postgresinstancemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.postgresinstancemanager.users.LoginRequest;
import com.tejas.postgresinstancemanager.users.RegisterRequest;
import com.tejas.postgresinstancemanager.worker.OperationCompletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationAndOwnershipIntegrationTests {

    private static final String TEST_SIGNING_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    private static final String ADMIN_PASSWORD = "integration-admin-password";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        TestPostgres.configure(registry, POSTGRES);
        registry.add("app.security.jwt-signing-key", () -> TEST_SIGNING_KEY);
        registry.add("app.security.credential-encryption-key", () -> TEST_SIGNING_KEY);
        registry.add("app.bootstrap.admin-username", () -> "integration-admin");
        registry.add("app.bootstrap.admin-password", () -> ADMIN_PASSWORD);
    }

    @AfterAll
    static void stopPostgres() {
        TestPostgres.stop(POSTGRES);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperationCompletionService operationCompletionService;

    @Test
    void registrationHashesPasswordAndCannotAssignAdministratorRole() throws Exception {
        String password = "viewer-registration-password";
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"viewer-one","password":"%s","role":"ADMINISTRATOR"}
                                """.formatted(password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID userId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?",
                String.class,
                userId);

        assertThat(storedHash).isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, storedHash)).isTrue();
        String token = login("viewer-one", password);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"viewer-one","password":"not-the-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void registrationValidatesFieldsAndRejectsDuplicateUsernames() throws Exception {
        String request = """
                {"username":"duplicate-user","password":"registration-password"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bad-user","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void usersCannotReadAnotherOwnersInstancesOrOperations() throws Exception {
        UUID owner = register("resource-owner", "resource-owner-password");
        String otherUserPassword = registerAndGetPassword("resource-reader");
        UUID otherUser = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                UUID.class,
                "resource-reader");
        jdbcTemplate.update("UPDATE app_user SET role = 'OPERATOR' WHERE id = ?", otherUser);
        String otherUserToken = login("resource-reader", otherUserPassword);
        UUID instanceId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO managed_instance
                    (id, owner_id, display_name, postgres_version, state)
                VALUES (?, ?, ?, ?, ?)
                """,
                instanceId,
                owner,
                "private-instance",
                "17.6",
                "READY");
        jdbcTemplate.update(
                """
                INSERT INTO operation (id, owner_id, requester_id, instance_id, operation_type, state)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                operationId,
                owner,
                owner,
                instanceId,
                "BACKUP",
                "SUCCEEDED");
        jdbcTemplate.update(
                """
                INSERT INTO backup_artifact
                    (id, owner_id, source_instance_id, operation_id, state, size_bytes, checksum_sha256)
                VALUES (?, ?, ?, ?, 'READY', 1, repeat('a', 64))
                """,
                UUID.randomUUID(),
                owner,
                instanceId,
                operationId);

        mockMvc.perform(get("/api/instances/{id}", instanceId)
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."));
        mockMvc.perform(get("/api/operations/{id}", operationId)
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isNotFound());
        UUID backupId = jdbcTemplate.queryForObject(
                "SELECT id FROM backup_artifact WHERE operation_id = ?", UUID.class, operationId);
        mockMvc.perform(get("/api/backups/{id}", backupId)
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/instances/{id}/stop", instanceId)
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM managed_instance WHERE id = ?",
                String.class,
                instanceId)).isEqualTo("READY");
        mockMvc.perform(get("/api/instances")
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void administratorRoleChangesAreAuthorizedAndAudited() throws Exception {
        UUID viewerId = register("role-target", "role-target-password");
        String adminToken = login("integration-admin", ADMIN_PASSWORD);
        String correlationId = "role-change-correlation";

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username").value(hasItem("role-target")));
        mockMvc.perform(patch("/api/admin/users/{id}/role", viewerId)
                        .header("Authorization", bearer(adminToken))
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE actor_id IS NOT NULL AND action = 'USER_ROLE_CHANGED'",
                Integer.class);
        assertThat(auditCount).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM audit_event WHERE action = 'USER_ROLE_CHANGED'",
                String.class)).isEqualTo(correlationId);

        String operatorToken = login("role-target", "role-target-password");
        mockMvc.perform(get("/api/activity")
                        .header("Authorization", bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("USER_ROLE_CHANGED"))
                .andExpect(jsonPath("$[0].correlationId").value(correlationId));
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(operatorToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
        UUID administratorId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                UUID.class,
                "integration-admin");
        mockMvc.perform(patch("/api/admin/users/{id}/role", administratorId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredLeasesRetryThenRestoreInstanceAfterAttemptsAreExhausted() throws Exception {
        UUID ownerId = register("lease-owner", "lease-owner-password");
        UUID instanceId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO managed_instance
                    (id, owner_id, display_name, postgres_version, state)
                VALUES (?, ?, 'lease-instance', '17.6', 'STOPPING')
                """,
                instanceId,
                ownerId);
        jdbcTemplate.update(
                """
                INSERT INTO operation
                    (id, owner_id, requester_id, instance_id, operation_type, state, attempt_count, max_attempts,
                     worker_id, lease_token, lease_until, previous_instance_state)
                VALUES (?, ?, ?, ?, 'BACKUP', 'RUNNING', 1, 3, 'stopped-worker',
                        gen_random_uuid(), CURRENT_TIMESTAMP - INTERVAL '1 minute', 'READY')
                """,
                operationId,
                ownerId,
                ownerId,
                instanceId);

        operationCompletionService.recoverExpiredLeases();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM operation WHERE id = ?", String.class, operationId))
                .isEqualTo("RETRY_WAIT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM operation WHERE id = ?", Integer.class, operationId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at > CURRENT_TIMESTAMP FROM operation WHERE id = ?",
                Boolean.class,
                operationId)).isTrue();

        jdbcTemplate.update(
                """
                UPDATE operation
                SET state = 'RUNNING', attempt_count = 3, lease_token = gen_random_uuid(),
                    lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id = ?
                """,
                operationId);
        operationCompletionService.recoverExpiredLeases();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM operation WHERE id = ?", String.class, operationId))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM managed_instance WHERE id = ?", String.class, instanceId))
                .isEqualTo("READY");
    }

    @Test
    void correlationIdsReachErrorResponsesAndRemainOwnerScopedInActivity() throws Exception {
        String correlationId = "m5-trace-123";
        mockMvc.perform(get("/api/auth/me")
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-ID", correlationId))
                .andExpect(jsonPath("$.correlationId").value(correlationId));

        UUID ownerId = register("activity-owner", "activity-owner-password");
        UUID otherOwnerId = register("activity-other", "activity-other-password");
        String ownerToken = login("activity-owner", "activity-owner-password");
        UUID ownerOperationId = insertAuditedOperation(ownerId, "owner-event", correlationId);
        UUID otherOperationId = insertAuditedOperation(otherOwnerId, "other-event", "other-trace");

        String activity = mockMvc.perform(get("/api/activity")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode entries = objectMapper.readTree(activity);
        assertThat(entries.toString()).contains(ownerOperationId.toString(), correlationId);
        assertThat(entries.toString()).doesNotContain(otherOperationId.toString(), "other-trace");
    }

    @Test
    void healthIsPublicAndOperationalMetricsRequireAdministrator() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/metrics/pim.operations.in_state"))
                .andExpect(status().isUnauthorized());

        String adminToken = login("integration-admin", ADMIN_PASSWORD);
        mockMvc.perform(get("/actuator/metrics/pim.operations.in_state")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("pim.operations.in_state"));
    }

    private UUID insertAuditedOperation(UUID ownerId, String displayName, String correlationId) {
        UUID instanceId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO managed_instance
                    (id, owner_id, display_name, postgres_version, state)
                VALUES (?, ?, ?, '17.6', 'READY')
                """,
                instanceId,
                ownerId,
                displayName);
        jdbcTemplate.update(
                """
                INSERT INTO operation
                    (id, owner_id, requester_id, instance_id, operation_type, state, correlation_id)
                VALUES (?, ?, ?, ?, 'BACKUP', 'SUCCEEDED', ?)
                """,
                operationId,
                ownerId,
                ownerId,
                instanceId,
                correlationId);
        jdbcTemplate.update(
                """
                INSERT INTO audit_event (id, action, resource_type, resource_id, details, correlation_id)
                VALUES (?, 'OPERATION_SUCCEEDED', 'OPERATION', ?, '{}'::jsonb, ?)
                """,
                UUID.randomUUID(),
                operationId,
                correlationId);
        return operationId;
    }

    private UUID register(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, password))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode user = objectMapper.readTree(response);
        return UUID.fromString(user.get("id").asText());
    }

    private String registerAndGetPassword(String username) throws Exception {
        register(username, "resource-reader-password");
        return "resource-reader-password";
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
