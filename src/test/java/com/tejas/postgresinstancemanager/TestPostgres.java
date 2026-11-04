package com.tejas.postgresinstancemanager;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

final class TestPostgres {

    private TestPostgres() {
    }

    static void configure(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        String externalUrl = System.getenv("TEST_DATABASE_URL");
        String externalUsername = System.getenv("TEST_DATABASE_USERNAME");
        String externalPassword = System.getenv("TEST_DATABASE_PASSWORD");
        boolean hasExternalUrl = externalUrl != null && !externalUrl.isBlank();
        boolean hasExternalCredentials =
                (externalUsername != null && !externalUsername.isBlank())
                        || (externalPassword != null && !externalPassword.isBlank());

        if (hasExternalUrl) {
            if (externalUsername == null || externalUsername.isBlank()
                    || externalPassword == null || externalPassword.isBlank()) {
                throw new IllegalStateException(
                        "TEST_DATABASE_URL requires TEST_DATABASE_USERNAME and TEST_DATABASE_PASSWORD.");
            }
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> externalUsername);
            registry.add("spring.datasource.password", () -> externalPassword);
            return;
        }
        if (hasExternalCredentials) {
            throw new IllegalStateException(
                    "TEST_DATABASE_USERNAME and TEST_DATABASE_PASSWORD require TEST_DATABASE_URL.");
        }

        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static void stop(PostgreSQLContainer<?> postgres) {
        if (postgres.isRunning()) {
            postgres.stop();
        }
    }
}
