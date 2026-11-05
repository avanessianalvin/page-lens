package com.pagelens.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Throwaway PostgreSQL for integration tests, wired into the datasource via {@code @ServiceConnection}.
 * Shared with app modules through the core test-jar. Pair with
 * {@code @Testcontainers(disabledWithoutDocker = true)} so tests skip (not fail) when Docker is down.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }
}
