package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full application against a throwaway Postgres (Testcontainers),
 * so the build needs Docker but not a manually started docker-compose stack.
 */
@SpringBootTest
@Import(PymkApiApplicationTests.PostgresConfig.class)
class PymkApiApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16")
                            .asCompatibleSubstituteFor("postgres"));
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoads() {
        // The Spring context starts, Flyway migrates, and Hibernate validates
        // the entity mappings against the migrated schema.
    }

    @Test
    void flywayCreatedCoreTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tables).contains("members", "connections", "member_events");
    }
}
