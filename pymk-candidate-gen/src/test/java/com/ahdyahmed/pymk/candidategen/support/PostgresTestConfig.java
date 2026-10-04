package com.ahdyahmed.pymk.candidategen.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real pgvector-enabled Postgres for candidate-generation integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                        .asCompatibleSubstituteFor("postgres"));
    }
}
