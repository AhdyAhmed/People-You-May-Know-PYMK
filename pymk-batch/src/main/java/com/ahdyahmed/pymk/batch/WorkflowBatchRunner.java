package com.ahdyahmed.pymk.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finite orchestration adapter. Deliberately cannot activate, roll back or run legacy writers. */
public final class WorkflowBatchRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("request.json receipt.json required");
        var json = new ObjectMapper();
        var request = json.readTree(Files.readAllBytes(Path.of(args[0])));
        String jobName = request.path("job").asText();
        if (!Set.of("embeddingRefreshJob", "servingReleaseJob", "liveFeatureReconciliationJob").contains(jobName))
            throw new IllegalArgumentException("Unsupported workflow job");
        var parameters = request.path("parameters");
        if (jobName.equals("servingReleaseJob") && !Set.of("export", "export-v2").contains(parameters.path("action").asText()))
            throw new IllegalArgumentException("Only immutable exports are allowed");
        String revision = request.path("sourceRevision").asText();
        boolean reconciliation = jobName.equals("liveFeatureReconciliationJob");
        if (!reconciliation && !revision.matches("[0-9]+")) throw new IllegalArgumentException("Explicit source revision required");
        try (var context = SpringApplication.run(PymkBatchApplication.class, "--spring.batch.job.enabled=false")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            if (!reconciliation) checkRevision(jdbc, revision);
            var builder = new JobParametersBuilder();
            parameters.fields().forEachRemaining(item -> {
                if (!item.getValue().isTextual()) throw new IllegalArgumentException("String parameters required");
                builder.addString(item.getKey(), item.getValue().asText());
            });
            var execution = context.getBean(JobLauncher.class).run(context.getBean(jobName, Job.class), builder.toJobParameters());
            if (execution.getStatus() != BatchStatus.COMPLETED || !"COMPLETED".equals(execution.getExitStatus().getExitCode()))
                throw new IllegalStateException("Batch execution did not complete: " + execution.getId());
            if (!reconciliation) checkRevision(jdbc, revision);
            if (jobName.equals("embeddingRefreshJob")) {
                var manifest = EmbeddingReleaseManifest.read(parameters.path("manifestPath").asText());
                var row = jdbc.queryForMap("SELECT state, source_fingerprint FROM pymk_serving_releases WHERE release_id=?", manifest.id());
                if (!Set.of("STAGED", "READY").contains(row.get("state")) || !revision.equals(row.get("source_fingerprint")))
                    throw new IllegalStateException("Staged generation does not match requested source");
            }
            var receipt = json.createObjectNode().put("job", jobName).put("executionId", execution.getId())
                    .put("status", "COMPLETED").put("sourceRevision", revision).put("automaticPromotion", false);
            if (reconciliation) {
                receipt.remove("sourceRevision");
                receipt.set("watermarks", parameters);
                long generation = execution.getStepExecutions().stream()
                        .filter(step -> step.getExecutionContext().containsKey("liveGeneration"))
                        .mapToLong(step -> step.getExecutionContext().getLong("liveGeneration"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Missing reconciled generation"));
                receipt.put("liveGeneration", generation);
            }
            Files.writeString(Path.of(args[1]), json.writeValueAsString(receipt), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    private static void checkRevision(JdbcTemplate jdbc, String expected) {
        if (!expected.equals(jdbc.queryForObject("SELECT revision::text FROM pymk_source_revision", String.class)))
            throw new IllegalStateException("Source revision changed; submit a new reviewed work manifest");
    }
}
