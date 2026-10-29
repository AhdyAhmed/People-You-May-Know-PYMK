package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class OutboxMigrationTest {
    @Test void upgradesPopulatedDay31SchemaWithoutRewritingHistory() {
        try (var postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).target("8").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO members(id, full_name) VALUES (1, 'A'), (2, 'B')");
            jdbc.update("INSERT INTO member_events(actor_member_id, target_member_id, type) VALUES (1, 2, 'PROFILE_VIEW')");
            Long revision = jdbc.queryForObject("SELECT revision FROM pymk_source_revision", Long.class);
            assertThat(Flyway.configure().dataSource(source).target("9").load().migrate().migrationsExecuted).isEqualTo(1);
            var flyway = Flyway.configure().dataSource(source).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM member_events", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_consumer_policy", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT revision FROM pymk_source_revision", Long.class)).isEqualTo(revision);
        }
    }
}
