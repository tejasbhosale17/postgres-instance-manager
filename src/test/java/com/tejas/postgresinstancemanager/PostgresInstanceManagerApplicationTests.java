package com.tejas.postgresinstancemanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PostgresInstanceManagerApplicationTests {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        TestPostgres.configure(registry, POSTGRES);
        registry.add("app.security.jwt-signing-key",
                () -> "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
    }

    @AfterAll
    static void stopPostgres() {
        TestPostgres.stop(POSTGRES);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsAndAppliesInitialMigration() {
        String purpose = jdbcTemplate.queryForObject(
                "SELECT metadata_value FROM platform_metadata WHERE metadata_key = ?",
                String.class,
                "schema-purpose");

        assertThat(purpose).isEqualTo("postgres-instance-management-control-plane");
    }
}
