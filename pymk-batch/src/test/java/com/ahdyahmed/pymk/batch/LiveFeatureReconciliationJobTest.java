package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
@Import(PostgresTestConfig.class)
class LiveFeatureReconciliationJobTest {
    @Autowired JobLauncher launcher;
    @Autowired @Qualifier("liveFeatureReconciliationJob") Job job;
    @Autowired JdbcTemplate jdbc;

    @Test void finiteJobActivatesNewGenerationAndRecordsExplicitKnowledgeBoundary() throws Exception {
        long before = jdbc.queryForObject("SELECT generation FROM live_projection_active", Long.class);
        var execution = launcher.run(job, parameters("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z", "2026-09-30T00:00:00Z"));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long after = jdbc.queryForObject("SELECT generation FROM live_projection_active", Long.class);
        assertThat(after).isGreaterThan(before);
        assertThat(execution.getStepExecutions().iterator().next().getExecutionContext().getLong("liveGeneration")).isEqualTo(after);
        assertThat(jdbc.queryForObject("SELECT knowledge_cutoff IS NOT NULL FROM live_projection_generation WHERE id = ?", Boolean.class, after)).isTrue();
    }

    @Test void invalidWindowOrFutureKnowledgeCannotActivateState() {
        long before = jdbc.queryForObject("SELECT generation FROM live_projection_active", Long.class);
        assertThatThrownBy(() -> launcher.run(job, parameters("2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")))
                .isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> launcher.run(job, parameters("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z", "2099-01-01T00:00:00Z")))
                .isInstanceOf(JobParametersInvalidException.class);
        assertThat(jdbc.queryForObject("SELECT generation FROM live_projection_active", Long.class)).isEqualTo(before);
    }

    private JobParameters parameters(String start, String end, String cutoff) {
        return new JobParametersBuilder().addString("windowStart", start).addString("windowEnd", end)
                .addString("knowledgeCutoff", cutoff).addString("runId", UUID.randomUUID().toString()).toJobParameters();
    }
}
