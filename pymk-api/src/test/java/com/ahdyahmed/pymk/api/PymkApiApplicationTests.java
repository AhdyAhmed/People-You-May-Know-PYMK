package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full application against a throwaway Postgres (Testcontainers),
 * so the build needs Docker but not a manually started docker-compose stack.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class PymkApiApplicationTests {

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
