package com.tejas.postgresinstancemanager;

import com.tejas.postgresinstancemanager.operations.OperationJob;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OperationClaimConcurrencyIntegrationTests {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        TestPostgres.configure(registry, POSTGRES);
        registry.add("app.security.jwt-signing-key", () -> TEST_KEY);
        registry.add("app.security.credential-encryption-key", () -> TEST_KEY);
        registry.add("app.worker.poll-delay-ms", () -> 60_000);
        registry.add("app.worker.recovery-delay-ms", () -> 60_000);
        registry.add("app.worker.reconcile-delay-ms", () -> 60_000);
    }

    @AfterAll
    static void stopPostgres() {
        TestPostgres.stop(POSTGRES);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperationRepository operationRepository;

    @Test
    void concurrentWorkersCannotClaimTheSameOperation() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_user (id, username, password_hash, role)
                VALUES (?, ?, 'test-hash', 'OPERATOR')
                """,
                ownerId,
                "claim-owner");
        jdbcTemplate.update(
                """
                INSERT INTO managed_instance
                    (id, owner_id, display_name, postgres_version, state)
                VALUES (?, ?, 'claim-instance', '17.6', 'PROVISIONING')
                """,
                instanceId,
                ownerId);
        jdbcTemplate.update(
                """
                INSERT INTO operation (id, owner_id, instance_id, operation_type, state)
                VALUES (?, ?, ?, 'CREATE_INSTANCE', 'QUEUED')
                """,
                operationId,
                ownerId,
                instanceId);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<OperationJob>>> claims = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String workerId = "test-worker-" + i;
                claims.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return operationRepository.claimNext(workerId, Duration.ofMinutes(2));
                }));
            }
            ready.await();
            start.countDown();
            List<Optional<OperationJob>> results = List.of(
                    claims.get(0).get(),
                    claims.get(1).get());

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT state FROM operation WHERE id = ?", String.class, operationId))
                    .isEqualTo("RUNNING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT lease_token FROM operation WHERE id = ?", UUID.class, operationId))
                    .isNotNull();
        } finally {
            workers.shutdownNow();
        }
    }
}
