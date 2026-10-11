package com.ahdyahmed.pymk.featurestore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL/pgvector service for feature-store migration and mapping tests. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                        .asCompatibleSubstituteFor("postgres"));
    }
}
