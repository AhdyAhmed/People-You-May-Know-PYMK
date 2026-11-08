package com.ahdyahmed.pymk.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fenced finite adapter; activation requires explicit M7 approval, never legacy activation or rollback. */
public final class WorkflowBatchRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("request.json receipt.json required");
        var json = new ObjectMapper();
        var request = json.readTree(Files.readAllBytes(Path.of(args[0])));
        String jobName = request.path("job").asText();
        if (!Set.of("embeddingRefreshJob", "servingReleaseJob", "liveFeatureReconciliationJob").contains(jobName))
            throw new IllegalArgumentException("Unsupported workflow job");
        var parameters = request.path("parameters");
        boolean approvedActivation = request.path("approvedActivation").asBoolean(false)
                && "true".equals(System.getenv("PYMK_WORKFLOW_ALLOW_ACTIVATION"))
                && "activate-approved".equals(parameters.path("action").asText());
        if (jobName.equals("servingReleaseJob") && !Set.of("export", "export-v2").contains(parameters.path("action").asText()) && !approvedActivation)
            throw new IllegalArgumentException("Only immutable exports are allowed");
        String revision = request.path("sourceRevision").asText();
        boolean reconciliation = jobName.equals("liveFeatureReconciliationJob");
        if (!reconciliation && !revision.matches("[0-9]+")) throw new IllegalArgumentException("Explicit source revision required");
        try (var context = SpringApplication.run(PymkBatchApplication.class, "--spring.batch.job.enabled=false")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var builder = new JobParametersBuilder();
            parameters.fields().forEachRemaining(item -> {
                if (!item.getValue().isTextual()) throw new IllegalArgumentException("String parameters required");
                builder.addString(item.getKey(), item.getValue().asText());
            });
            if (!reconciliation) builder.addString("workflowSourceRevision", revision);
            var jobParameters = builder.toJobParameters();
            var identity = new TreeMap<String, String>();
            parameters.fields().forEachRemaining(item -> identity.put(item.getKey(), item.getValue().asText()));
            if (!reconciliation) identity.put("workflowSourceRevision", revision);
            // Session lock spans the job and receipt publication, including direct manual adapters.
            try (var fence = context.getBean(DataSource.class).getConnection();
                 var lock = fence.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,440044))")) {
            lock.setString(1, jobName + json.writeValueAsString(identity));
            try (var result = lock.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Workflow job is already owned by another worker");
            }
            try {
            var execution = context.getBean(JobRepository.class).getLastJobExecution(jobName, jobParameters);
            boolean reused = execution != null && execution.getStatus() == BatchStatus.COMPLETED;
            if (reused) {
                // Repository lookup loads step headers, not their persisted execution contexts.
                execution = java.util.Objects.requireNonNull(context.getBean(JobExplorer.class).getJobExecution(execution.getId()));
            }
            if (!reused) {
                if (execution != null && (execution.isRunning() || execution.getStatus() == BatchStatus.UNKNOWN))
                    throw new IllegalStateException("Uncertain orphan execution; reconcile its owner before restarting: " + execution.getId());
                if (!reconciliation) checkRevision(jdbc, revision);
                execution = context.getBean(JobLauncher.class).run(context.getBean(jobName, Job.class), jobParameters);
            }
            if (execution.getStatus() != BatchStatus.COMPLETED || !"COMPLETED".equals(execution.getExitStatus().getExitCode()))
                throw new IllegalStateException("Batch execution did not complete: " + execution.getId());
            if (!reconciliation && !reused) checkRevision(jdbc, revision);
            if (jobName.equals("embeddingRefreshJob")) {
                var manifest = EmbeddingReleaseManifest.read(parameters.path("manifestPath").asText());
                var row = jdbc.queryForMap("SELECT state, source_fingerprint FROM pymk_serving_releases WHERE release_id=?", manifest.id());
                if (!Set.of("STAGED", "READY").contains(row.get("state")) || !revision.equals(row.get("source_fingerprint")))
                    throw new IllegalStateException("Staged generation does not match requested source");
            }
            var receipt = json.createObjectNode().put("job", jobName).put("executionId", execution.getId())
                    .put("instanceId", execution.getJobInstance().getInstanceId())
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
            Path output = Path.of(args[1]);
            if (Files.exists(output)) {
                if (!json.readTree(Files.readAllBytes(output)).equals(receipt))
                    throw new IllegalStateException("Existing receipt does not match completed execution");
            } else {
                Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".workflow-receipt-", ".json");
                try {
                    Files.writeString(temporary, json.writeValueAsString(receipt));
                    Files.createLink(output, temporary);
                } finally { Files.deleteIfExists(temporary); }
            }
            } finally {
                try (var unlock = fence.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,440044))")) {
                    unlock.setString(1, jobName + json.writeValueAsString(identity));
                    unlock.execute();
                }
            }
            }
        }
    }

    private static void checkRevision(JdbcTemplate jdbc, String expected) {
        if (!expected.equals(jdbc.queryForObject("SELECT revision::text FROM pymk_source_revision", String.class)))
            throw new IllegalStateException("Source revision changed; submit a new reviewed work manifest");
    }
}
