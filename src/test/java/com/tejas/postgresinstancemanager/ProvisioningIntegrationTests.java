package com.tejas.postgresinstancemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.tejas.postgresinstancemanager.worker.InstanceReconciler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tejas.postgresinstancemanager.worker.DockerJavaManagedPostgres.INSTANCE_LABEL;
import static com.tejas.postgresinstancemanager.worker.DockerJavaManagedPostgres.PLATFORM_LABEL;
import static com.tejas.postgresinstancemanager.worker.DockerJavaManagedPostgres.PLATFORM_VALUE;
import static com.tejas.postgresinstancemanager.worker.DockerJavaManagedPostgres.RESOURCE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class ProvisioningIntegrationTests {

    private static final String TEST_SIGNING_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    private static final String ADMIN_PASSWORD = "provisioning-admin-password";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        TestPostgres.configure(registry, POSTGRES);
        registry.add("app.security.jwt-signing-key", () -> TEST_SIGNING_KEY);
        registry.add("app.security.credential-encryption-key", () -> TEST_SIGNING_KEY);
        registry.add("app.bootstrap.admin-username", () -> "provisioning-admin");
        registry.add("app.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("app.backup.directory", () -> "target/test-backups/provisioning");
    }

    @AfterAll
    static void stopPostgres() {
        TestPostgres.stop(POSTGRES);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DockerClient dockerClient;

    @Autowired
    private InstanceReconciler instanceReconciler;

    @Test
    void provisionsLabeledPostgresWithPersistentVolumeAndEncryptedCredentials() throws Exception {
        UUID instanceId = null;
        String databasePassword = "postgres-test-" + UUID.randomUUID();
        String token = loginAdministrator();
        try {
            String createRequest = objectMapper.writeValueAsString(
                    Map.of("name", "integration-postgres", "databasePassword", databasePassword));
            String createIdempotencyKey = "integration-create-" + UUID.randomUUID();
            String correlationId = "provision-test-" + UUID.randomUUID();
            String acceptedResponse = mockMvc.perform(post("/api/instances")
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", createIdempotencyKey)
                            .header("X-Correlation-ID", correlationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createRequest))
                    .andExpect(status().isAccepted())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Correlation-ID", correlationId))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode accepted = objectMapper.readTree(acceptedResponse);
            UUID operationId = UUID.fromString(accepted.get("operationId").asText());
            assertThat(acceptedResponse).doesNotContain(databasePassword);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT correlation_id FROM operation WHERE id = ?", String.class, operationId))
                    .isEqualTo(correlationId);
            mockMvc.perform(post("/api/instances")
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", createIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createRequest))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.operationId").value(operationId.toString()));
            mockMvc.perform(post("/api/instances")
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", createIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", "different-name", "databasePassword", databasePassword))))
                    .andExpect(status().isConflict());

            instanceId = jdbcTemplate.queryForObject(
                    "SELECT instance_id FROM operation WHERE id = ?",
                    UUID.class,
                    operationId);
            JsonNode operation = awaitOperation(token, operationId);
            assertThat(operation.get("state").asText()).isEqualTo("SUCCEEDED");
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM audit_event
                    WHERE resource_type = 'OPERATION' AND resource_id = ? AND correlation_id = ?
                    """,
                    Integer.class,
                    operationId,
                    correlationId)).isEqualTo(2);
            String auditDetails = jdbcTemplate.queryForObject(
                    "SELECT string_agg(details::text, ' ') FROM audit_event WHERE resource_id = ?",
                    String.class,
                    operationId);
            assertThat(auditDetails).doesNotContain(databasePassword, "credentialCiphertext");

            JsonNode instance = objectMapper.readTree(mockMvc.perform(get("/api/instances/{id}", instanceId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            int hostPort = instance.get("hostPort").asInt();
            assertThat(instance.get("state").asText()).isEqualTo("READY");
            assertThat(instance.get("databaseUsername").asText()).isEqualTo("postgres");
            assertThat(instance.toString()).doesNotContain(databasePassword);

            String storedPayload = jdbcTemplate.queryForObject(
                    "SELECT payload::text FROM operation WHERE id = ?",
                    String.class,
                    operationId);
            assertThat(storedPayload).doesNotContain(databasePassword);
            verifyDockerLabels(instanceId);
            verifyDatabaseConnection(hostPort, databasePassword);

            String backupIdempotencyKey = "backup-" + UUID.randomUUID();
            String backupResponse = mockMvc.perform(post("/api/instances/{id}/backups", instanceId)
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", backupIdempotencyKey))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            UUID backupOperationId = UUID.fromString(
                    objectMapper.readTree(backupResponse).get("operationId").asText());
            mockMvc.perform(post("/api/instances/{id}/backups", instanceId)
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", backupIdempotencyKey))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.operationId").value(backupOperationId.toString()));
            assertThat(awaitOperation(token, backupOperationId).get("state").asText()).isEqualTo("SUCCEEDED");
            UUID backupId = jdbcTemplate.queryForObject(
                    "SELECT id FROM backup_artifact WHERE operation_id = ?",
                    UUID.class,
                    backupOperationId);
            JsonNode backup = objectMapper.readTree(mockMvc.perform(get("/api/backups/{id}", backupId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            assertThat(backup.get("state").asText()).isEqualTo("READY");
            assertThat(backup.get("sizeBytes").asLong()).isPositive();
            assertThat(backup.get("checksumSha256").asText()).matches("[0-9a-f]{64}");
            byte[] archive = mockMvc.perform(get("/api/backups/{id}/content", backupId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            assertThat(archive).hasSize((int) backup.get("sizeBytes").asLong());

            setProbeValue(hostPort, databasePassword, 99);
            mockMvc.perform(post("/api/instances/{id}/restore", instanceId)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "backupId", backupId,
                                    "confirmDataReplacement", false))))
                    .andExpect(status().isBadRequest());
            assertThat(getInstance(token, instanceId).get("state").asText()).isEqualTo("READY");

            jdbcTemplate.update(
                    "UPDATE backup_artifact SET checksum_sha256 = ? WHERE id = ?",
                    "0".repeat(64),
                    backupId);
            JsonNode failedRestore = objectMapper.readTree(mockMvc.perform(
                            post("/api/instances/{id}/restore", instanceId)
                                    .header("Authorization", bearer(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of(
                                            "backupId", backupId,
                                            "confirmDataReplacement", true))))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            JsonNode failedRestoreOperation = awaitOperationState(
                    token,
                    UUID.fromString(failedRestore.get("operationId").asText()),
                    "FAILED");
            assertThat(failedRestoreOperation.get("errorCode").asText()).isEqualTo("BACKUP_CHECKSUM_MISMATCH");
            assertThat(getInstance(token, instanceId).get("state").asText()).isEqualTo("READY");
            assertThat(readProbeValue(hostPort, databasePassword)).isEqualTo(99);

            jdbcTemplate.update(
                    "UPDATE backup_artifact SET checksum_sha256 = ? WHERE id = ?",
                    backup.get("checksumSha256").asText(),
                    backupId);
            JsonNode restoreOperation = objectMapper.readTree(mockMvc.perform(
                            post("/api/instances/{id}/restore", instanceId)
                                    .header("Authorization", bearer(token))
                                    .header("Idempotency-Key", "restore-" + UUID.randomUUID())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of(
                                            "backupId", backupId,
                                            "confirmDataReplacement", true))))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            assertThat(awaitOperation(
                    token, UUID.fromString(restoreOperation.get("operationId").asText()))
                    .get("state").asText()).isEqualTo("SUCCEEDED");
            verifyDatabaseConnection(getInstance(token, instanceId).get("hostPort").asInt(), databasePassword);

            String stopIdempotencyKey = "integration-stop-" + UUID.randomUUID();
            JsonNode stopOperation = requestLifecycle(
                    token, post("/api/instances/{id}/stop", instanceId), stopIdempotencyKey);
            assertThat(stopOperation
                    .get("state").asText()).isEqualTo("SUCCEEDED");
            mockMvc.perform(post("/api/instances/{id}/stop", instanceId)
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", stopIdempotencyKey))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.operationId").value(stopOperation.get("id").asText()));
            assertThat(getInstance(token, instanceId).get("state").asText()).isEqualTo("STOPPED");
            mockMvc.perform(post("/api/instances/{id}/stop", instanceId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isConflict());

            assertThat(requestLifecycle(token, post("/api/instances/{id}/start", instanceId))
                    .get("state").asText()).isEqualTo("SUCCEEDED");
            JsonNode started = getInstance(token, instanceId);
            assertThat(started.get("state").asText()).isEqualTo("READY");
            verifyDatabaseConnection(started.get("hostPort").asInt(), databasePassword);

            assertThat(requestLifecycle(token, post("/api/instances/{id}/restart", instanceId))
                    .get("state").asText()).isEqualTo("SUCCEEDED");
            assertThat(getInstance(token, instanceId).get("state").asText()).isEqualTo("READY");

            List<Container> managedContainers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(
                            PLATFORM_LABEL, PLATFORM_VALUE,
                            INSTANCE_LABEL, instanceId.toString(),
                            RESOURCE_LABEL, "container"))
                    .exec();
            assertThat(managedContainers).hasSize(1);
            dockerClient.stopContainerCmd(managedContainers.get(0).getId()).withTimeout(5).exec();
            instanceReconciler.reconcileInstances();
            assertThat(getInstance(token, instanceId).get("state").asText()).isEqualTo("STOPPED");
            assertThat(requestLifecycle(token, post("/api/instances/{id}/start", instanceId))
                    .get("state").asText()).isEqualTo("SUCCEEDED");

            mockMvc.perform(delete("/api/instances/{id}", instanceId)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmDataDeletion\":false}"))
                    .andExpect(status().isBadRequest());
            assertThat(getInstance(token, instanceId).get("state").asText()).isEqualTo("READY");

            assertThat(requestLifecycle(token, delete("/api/instances/{id}", instanceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirmDataDeletion\":true}"))
                    .get("state").asText()).isEqualTo("SUCCEEDED");
            JsonNode deleted = getInstance(token, instanceId);
            assertThat(deleted.get("state").asText()).isEqualTo("DELETED");
            assertThat(deleted.get("hostPort").isNull()).isTrue();
            UUID deletedInstanceId = instanceId;
            assertThat(dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(INSTANCE_LABEL, deletedInstanceId.toString()))
                    .exec()).isEmpty();
            assertThatThrownBy(() -> dockerClient.inspectVolumeCmd("pim-pg-data-" + deletedInstanceId).exec())
                    .isInstanceOf(NotFoundException.class);
        } finally {
            if (instanceId != null) {
                removeOnlyThisTestInstance(instanceId);
            }
        }
    }

    private JsonNode awaitOperation(String token, UUID operationId) throws Exception {
        return awaitOperationState(token, operationId, "SUCCEEDED");
    }

    private JsonNode awaitOperationState(String token, UUID operationId, String expectedState) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        JsonNode operation = null;
        while (System.nanoTime() < deadline) {
            String response = mockMvc.perform(get("/api/operations/{id}", operationId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            operation = objectMapper.readTree(response);
            String state = operation.get("state").asText();
            if (expectedState.equals(state)) {
                return operation;
            }
            if ("SUCCEEDED".equals(state) || "FAILED".equals(state)) {
                fail("Operation reached " + state + " instead of " + expectedState
                        + " with error code " + operation.get("errorCode").asText());
            }
            Thread.sleep(500);
        }
        fail("Provisioning did not finish before the test timeout.");
        return operation;
    }

    private JsonNode requestLifecycle(
            String token,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return requestLifecycle(token, request, null);
    }

    private JsonNode requestLifecycle(
            String token,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String idempotencyKey) throws Exception {
        request.header("Authorization", bearer(token));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID operationId = UUID.fromString(objectMapper.readTree(response).get("operationId").asText());
        return awaitOperation(token, operationId);
    }

    private JsonNode getInstance(String token, UUID instanceId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/instances/{id}", instanceId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private void verifyDockerLabels(UUID instanceId) {
        Map<String, String> containerLabels = Map.of(
                PLATFORM_LABEL, PLATFORM_VALUE,
                INSTANCE_LABEL, instanceId.toString(),
                RESOURCE_LABEL, "container");
        List<Container> containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(containerLabels)
                .exec();
        assertThat(containers).hasSize(1);
        assertThat(containers.get(0).getLabels()).containsAllEntriesOf(containerLabels);

        InspectVolumeResponse volume = dockerClient.inspectVolumeCmd("pim-pg-data-" + instanceId).exec();
        assertThat(volume.getLabels()).containsEntry(PLATFORM_LABEL, PLATFORM_VALUE)
                .containsEntry(INSTANCE_LABEL, instanceId.toString())
                .containsEntry(RESOURCE_LABEL, "volume");

        InspectContainerResponse container = dockerClient.inspectContainerCmd(containers.get(0).getId()).exec();
        assertThat(container.getMounts())
                .anySatisfy(mount -> {
                    assertThat(mount.getName()).isEqualTo("pim-pg-data-" + instanceId);
                    assertThat(mount.getDestination().toString()).isEqualTo("/var/lib/postgresql/data");
                });
    }

    private void verifyDatabaseConnection(int hostPort, String password) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + hostPort + "/postgres?connectTimeout=3";
        try (Connection connection = DriverManager.getConnection(url, "postgres", password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS provisioning_probe (value INTEGER NOT NULL)");
            statement.execute(
                    "INSERT INTO provisioning_probe (value) SELECT 42 "
                            + "WHERE NOT EXISTS (SELECT 1 FROM provisioning_probe)");
            try (ResultSet resultSet = statement.executeQuery("SELECT value FROM provisioning_probe")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(42);
            }
        }
    }

    private void setProbeValue(int hostPort, String password, int value) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + hostPort + "/postgres?connectTimeout=3";
        try (Connection connection = DriverManager.getConnection(url, "postgres", password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE provisioning_probe SET value = " + value);
        }
    }

    private int readProbeValue(int hostPort, String password) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + hostPort + "/postgres?connectTimeout=3";
        try (Connection connection = DriverManager.getConnection(url, "postgres", password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT value FROM provisioning_probe")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private void removeOnlyThisTestInstance(UUID instanceId) {
        Map<String, String> containerLabels = Map.of(
                PLATFORM_LABEL, PLATFORM_VALUE,
                INSTANCE_LABEL, instanceId.toString(),
                RESOURCE_LABEL, "container");
        List<Container> containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(containerLabels)
                .exec();
        for (Container container : containers) {
            if (!containerLabels.entrySet().stream()
                    .allMatch(label -> label.getValue().equals(container.getLabels().get(label.getKey())))) {
                continue;
            }
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(container.getId()).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                dockerClient.stopContainerCmd(container.getId()).withTimeout(5).exec();
            }
            dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
        }
        try {
            InspectVolumeResponse volume = dockerClient.inspectVolumeCmd("pim-pg-data-" + instanceId).exec();
            if (PLATFORM_VALUE.equals(volume.getLabels().get(PLATFORM_LABEL))
                    && instanceId.toString().equals(volume.getLabels().get(INSTANCE_LABEL))
                    && "volume".equals(volume.getLabels().get(RESOURCE_LABEL))) {
                dockerClient.removeVolumeCmd(volume.getName()).exec();
            }
        } catch (NotFoundException ignored) {
            // The worker may not have created a volume if provisioning failed early.
        }
    }

    private String loginAdministrator() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "provisioning-admin", "password", ADMIN_PASSWORD))))
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
