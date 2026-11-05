package com.tejas.postgresinstancemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
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

    @Test
    void provisionsLabeledPostgresWithPersistentVolumeAndEncryptedCredentials() throws Exception {
        UUID instanceId = null;
        String databasePassword = "postgres-test-" + UUID.randomUUID();
        String token = loginAdministrator();
        try {
            String acceptedResponse = mockMvc.perform(post("/api/instances")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", "integration-postgres", "databasePassword", databasePassword))))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode accepted = objectMapper.readTree(acceptedResponse);
            UUID operationId = UUID.fromString(accepted.get("operationId").asText());
            assertThat(acceptedResponse).doesNotContain(databasePassword);

            instanceId = jdbcTemplate.queryForObject(
                    "SELECT instance_id FROM operation WHERE id = ?",
                    UUID.class,
                    operationId);
            JsonNode operation = awaitOperation(token, operationId);
            assertThat(operation.get("state").asText()).isEqualTo("SUCCEEDED");

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

            assertThat(requestLifecycle(token, post("/api/instances/{id}/stop", instanceId))
                    .get("state").asText()).isEqualTo("SUCCEEDED");
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
            if ("SUCCEEDED".equals(state)) {
                return operation;
            }
            if ("FAILED".equals(state)) {
                fail("Provisioning failed with error code " + operation.get("errorCode").asText());
            }
            Thread.sleep(500);
        }
        fail("Provisioning did not finish before the test timeout.");
        return operation;
    }

    private JsonNode requestLifecycle(
            String token,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request.header("Authorization", bearer(token)))
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
