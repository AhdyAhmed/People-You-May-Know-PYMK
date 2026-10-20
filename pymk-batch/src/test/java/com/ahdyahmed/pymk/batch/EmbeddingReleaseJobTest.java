package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.ahdyahmed.pymk.featurestore.PairFeatureComputer;
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.ahdyahmed.pymk.lightranker.LightRankerProperties;
import com.ahdyahmed.pymk.lightranker.ReleaseLightRankerModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "pymk.batch.feature-computation.chunk-size=1"})
@Import(PostgresTestConfig.class)
class EmbeddingReleaseJobTest {
    private final JobLauncherTestUtils jobs = new JobLauncherTestUtils();
    @Autowired org.springframework.batch.core.launch.JobLauncher launcher;
    @Autowired org.springframework.batch.core.repository.JobRepository jobRepository;
    @Autowired @Qualifier("embeddingRefreshJob") Job job;
    @Autowired @Qualifier("servingReleaseJob") Job releaseJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired ServingReleaseService publisher;
    @Autowired ServingReleaseStore store;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean PairFeatureComputer computer;
    @TempDir Path directory;
    private static final Instant HISTORICAL = Instant.parse("2026-01-03T00:00:00Z");
    private static final Instant LIVE = Instant.parse("2026-02-01T00:00:00Z");
    private Path transform;
    private Path model;
    private Path baselineMetadata;
    private String version;

    @BeforeEach
    void seed() throws Exception {
        jobs.setJobLauncher(launcher);
        jobs.setJobRepository(jobRepository);
        jobs.setJob(job);
        jdbc.execute("TRUNCATE members, pymk_embedding_transforms, pymk_serving_releases, pymk_legacy_release_model RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO pymk_active_release(singleton) VALUES(true)");
        for (int id = 1; id <= 4; id++) {
            jdbc.update("INSERT INTO members(id,full_name,company,school,geo_region,created_at) VALUES(?,?,?,?,?, '2026-01-01T00:00:00Z')",
                    id, "Member " + id, id < 3 ? "Acme" : id == 3 ? "Bakery" : null,
                    id < 3 ? "Engineering University" : id == 3 ? "Cooking Academy" : null,
                    id < 3 ? "Cairo" : id == 3 ? "Paris" : null);
        }
        jdbc.update("INSERT INTO member_events(actor_member_id,target_member_id,type,occurred_at) VALUES(1,2,'INVITE_ACCEPTED','2026-01-05T00:00:00Z')");
        jdbc.update("INSERT INTO pymk_features VALUES(1,2,7,true,true,true,0.2,0, '2026-01-03T00:00:00Z','2026-01-03T00:00:00Z')");
        transform = directory.resolve("transform.json");
        Files.write(transform, resource("transform.json"));
        version = EmbeddingReleaseManifest.JSON.readTree(resource("transform.json")).path("payload").path("metadata").path("embeddingVersion").asText();
        model = directory.resolve("model.onnx");
        Files.write(model, Base64.getDecoder().decode(new String(resource("model.base64")).trim()));
        baselineMetadata = directory.resolve("baseline.json");
        Files.write(baselineMetadata, EmbeddingReleaseManifest.JSON.writeValueAsBytes(metadata("baseline-v1")));
    }

    @Test
    void stagesExportsPromotesAndRollsBackWithoutReplacingLegacyRows() throws Exception {
        Path history = manifest("history", HISTORICAL, 4);
        assertThat(run(history, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Path live = manifest("live", LIVE, 4);
        assertThat(run(live, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("pymk_release_features", "live")).isEqualTo(6);
        assertThat(store.current().managed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_serving_embeddings", Long.class)).isZero();
        Path metadata = releaseMetadata("live-model", "history");
        publisher.activate("live", 0, model, metadata, model, baselineMetadata);
        assertThat(store.current().id()).isEqualTo("live");
        assertThat(store.current().epoch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_serving_embeddings", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_serving_features", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT mutual_connection_count FROM pymk_features WHERE member_id=1 AND candidate_id=2", Integer.class)).isEqualTo(7);
        // Re-running the same immutable staged generation is deterministic.
        assertThat(run(live, 2).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("pymk_release_features", "live")).isEqualTo(6);

        var properties = new LightRankerProperties();
        properties.setModelPath(directory.resolve("missing.onnx"));
        properties.setMetadataPath(directory.resolve("missing.json"));
        try (var runtime = new ReleaseLightRankerModel(store, jdbc, EmbeddingReleaseManifest.JSON, properties)) {
            assertThat(runtime.modelVersion()).isEqualTo("live-model");
            assertThat(runtime.score(new float[][]{{0,1,1,1,1,0}})[0]).isBetween(0f, 1f);
            publisher.rollback("legacy", 1);
            assertThat(runtime.modelVersion()).isEqualTo("baseline-v1");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_serving_features", Long.class)).isEqualTo(1);
            publisher.rollback("live", 2);
            assertThat(runtime.modelVersion()).isEqualTo("live-model");
        }
        assertThatThrownBy(() -> publisher.rollback("legacy", 1)).hasMessageContaining("Stale expectedEpoch");
        assertThat(store.current().epoch()).isEqualTo(3);
    }

    @Test
    void resumesAfterACommittedMemberChunkAndRejectsChangedInputs() throws Exception {
        Path manifest = manifest("restart", LIVE, 4);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (((PairId) invocation.getArgument(0)).getMemberId() == 2L && fail.get()) throw new IllegalStateException("injected interruption");
            return invocation.callRealMethod();
        }).when(computer).compute(any(PairId.class), any(Instant.class), any(Instant.class), any(MemberEmbedding.class), any(MemberEmbedding.class));
        assertThat(run(manifest, 1).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT last_member_id FROM pymk_serving_releases WHERE release_id='restart'", Long.class)).isEqualTo(1);
        assertThat(count("pymk_release_features", "restart")).isEqualTo(2);
        assertThat(store.current().epoch()).isZero();
        fail.set(false);
        assertThat(run(manifest, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("pymk_release_features", "restart")).isEqualTo(6);
        Files.writeString(directory.resolve("restart.csv"), "changed");
        assertThat(run(manifest, 2).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(store.current().epoch()).isZero();
    }

    @Test
    void rejectsIncompleteCoverageSourceDriftAndInvalidModelWithoutActivation() throws Exception {
        assertThat(run(manifest("missing", LIVE, 3), 1).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(store.current().epoch()).isZero();
        assertThat(run(manifest("history", HISTORICAL, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(manifest("live", LIVE, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Path metadata = releaseMetadata("candidate", "history");
        var wrong = EmbeddingReleaseManifest.JSON.readTree(Files.readAllBytes(metadata));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.path("embeddingCompatibility")).put("transformSha256", "0".repeat(64));
        Path invalid = directory.resolve("invalid.json");
        Files.write(invalid, EmbeddingReleaseManifest.JSON.writeValueAsBytes(wrong));
        assertThatThrownBy(() -> publisher.activate("live", 0, model, invalid, model, baselineMetadata)).hasMessageContaining("frozen embedding transform");
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.path("embeddingCompatibility")).put("transformSha256", EmbeddingReleaseManifest.fileHash(transform));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.path("metrics").path("validation")).put("rocAuc", 0.1);
        Files.write(invalid, EmbeddingReleaseManifest.JSON.writeValueAsBytes(wrong));
        assertThatThrownBy(() -> publisher.activate("live", 0, model, invalid, model, baselineMetadata)).hasMessageContaining("quality gate");
        jdbc.update("UPDATE members SET company='Changed' WHERE id=1");
        assertThatThrownBy(() -> publisher.activate("live", 0, model, metadata, model, baselineMetadata)).hasMessageContaining("Source changed");
        assertThat(store.current().epoch()).isZero();
    }

    @Test
    void publicationWaitsForPinnedReaders() throws Exception {
        assertThat(run(manifest("history", HISTORICAL, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(manifest("live", LIVE, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Path metadata = releaseMetadata("model", "history");
        var pinned = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                try (var scope = store.pin()) {
                    pinned.countDown();
                    try { if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("Reader timed out"); }
                    catch (InterruptedException ex) { throw new RuntimeException(ex); }
                    assertThat(store.current().epoch()).isZero();
                }
                return null;
            }));
            assertThat(pinned.await(5, TimeUnit.SECONDS)).isTrue();
            var activation = workers.submit(() -> { publisher.activate("live", 0, model, metadata, model, baselineMetadata); return null; });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                long waiting;
                do {
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event='advisory'", Long.class);
                    if (waiting == 0) Thread.sleep(10);
                } while (waiting == 0 && System.nanoTime() < deadline);
                assertThat(waiting).isGreaterThan(0);
                assertThat(activation.isDone()).isFalse();
            } finally { finish.countDown(); }
            reader.get(5, TimeUnit.SECONDS);
            activation.get(10, TimeUnit.SECONDS);
            assertThat(store.current().id()).isEqualTo("live");
        }
    }

    @Test
    void exportsV2DirectionalUnconditionalLabelsThroughTheRunnableJob() throws Exception {
        // Existing seed: 1 -> 2 accepted on Jan 5, with no in-window invitation.
        event(1, 2, "INVITE_SENT", "2026-01-03T00:00:00Z"); // exclusive lower boundary
        event(1, 2, "INVITE_SENT", "2026-01-10T00:00:00.000001Z"); // after upper boundary
        event(2, 1, "INVITE_SENT", "2026-01-05T00:00:00Z");
        event(2, 1, "INVITE_SENT", "2026-01-05T00:00:00Z"); // duplicates remain binary
        event(2, 1, "INVITE_ACCEPTED", "2026-01-03T00:00:00Z");
        event(1, 3, "INVITE_SENT", "2026-01-10T00:00:00Z"); // inclusive upper boundary
        event(1, 3, "INVITE_ACCEPTED", "2026-01-10T00:00:00Z");
        event(1, 3, "INVITE_ACCEPTED", "2026-01-10T00:00:00Z");
        event(3, 2, "PROFILE_VIEW", "2026-01-05T00:00:00Z"); // wrong type, future feature signal
        event(3, 2, "PROFILE_VIEW", "2026-01-02T00:00:00Z"); // valid observation
        assertThat(run(manifest("history-v2", HISTORICAL, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        jobs.setJob(releaseJob);
        Path output = directory.resolve("v2.csv");
        assertThat(jobs.launchJob(new JobParametersBuilder().addString("action", "export-v2")
                .addString("releaseId", "history-v2").addString("datasetId", "dataset-v2")
                .addString("labelWindowEnd", "2026-01-10T00:00:00Z").addString("outputPath", output.toString())
                .toJobParameters()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var lines = Files.readAllLines(output);
        assertThat(lines).hasSize(7);
        assertThat(lines.getFirst()).isEqualTo(TrainingExample.CSV_V2_HEADER);
        assertThat(lines.stream().filter(s -> s.startsWith("2,1,2,")).findFirst().orElseThrow()).endsWith(",0,1");
        assertThat(lines.stream().filter(s -> s.startsWith("2,2,1,")).findFirst().orElseThrow()).endsWith(",1,0");
        assertThat(lines.stream().filter(s -> s.startsWith("2,1,3,")).findFirst().orElseThrow()).endsWith(",1,1");
        for (String pair : java.util.List.of("2,3,1,", "2,3,2,", "2,2,3,")) {
            assertThat(lines.stream().filter(s -> s.startsWith(pair)).findFirst().orElseThrow()).endsWith(",0,0");
        }
        assertThat(lines.stream().filter(s -> s.startsWith("2,3,2,")).findFirst().orElseThrow().split(",")[8]).isEqualTo("1");
        var provenance = EmbeddingReleaseManifest.JSON.readTree(Files.readAllBytes(Path.of(output + ".provenance.json")));
        assertThat(provenance.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(provenance.path("rowCount").asInt()).isEqualTo(6);
        assertThat(provenance.path("csvSha256").asText()).isEqualTo(EmbeddingReleaseManifest.fileHash(output));
        assertThat(provenance.path("cohortSha256").asText()).isEqualTo(EmbeddingReleaseManifest.sha256("1,2\n1,3\n2,1\n2,3\n3,1\n3,2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_training_datasets_v2", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_release_exports", Long.class)).isZero();
        assertThat(store.current().epoch()).isZero();
        Path v1 = directory.resolve("v1.csv");
        publisher.exportTraining("history-v2", Instant.parse("2026-01-10T00:00:00Z"), v1);
        assertThat(Files.readAllLines(v1).getFirst()).isEqualTo(TrainingExample.CSV_HEADER);
    }

    @Test
    void v2CorrectionsRequireNewIdentityAndNeverRewriteArchivedInputs() throws Exception {
        assertThat(run(manifest("history-v2", HISTORICAL, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Instant end = Instant.parse("2026-01-10T00:00:00Z");
        Path old = directory.resolve("old.csv");
        publisher.exportTrainingV2("history-v2", end, old, "original");
        byte[] archived = Files.readAllBytes(old);
        var before = EmbeddingReleaseManifest.JSON.readTree(Files.readAllBytes(Path.of(old + ".provenance.json")));
        assertThatThrownBy(() -> publisher.exportTrainingV2("history-v2", end, directory.resolve("reuse.csv"), "original"))
                .hasMessageContaining("Dataset ID is immutable");
        assertThat(directory.resolve("reuse.csv")).doesNotExist();
        assertThatThrownBy(() -> publisher.exportTrainingV2("history-v2", end, old, "other"))
                .hasMessageContaining("immutable");
        event(1, 2, "INVITE_SENT", "2026-01-09T00:00:00Z"); // late, corrected historical label
        Path corrected = directory.resolve("corrected.csv");
        publisher.exportTrainingV2("history-v2", end, corrected, "corrected");
        var after = EmbeddingReleaseManifest.JSON.readTree(Files.readAllBytes(Path.of(corrected + ".provenance.json")));
        assertThat(Files.readAllBytes(old)).isEqualTo(archived);
        assertThat(after.path("csvSha256")).isNotEqualTo(before.path("csvSha256"));
        assertThat(after.path("featureSourceRevision")).isEqualTo(before.path("featureSourceRevision"));
        assertThat(after.path("labelSourceRevision")).isNotEqualTo(before.path("labelSourceRevision"));
        assertThat(after.path("cohortSha256")).isEqualTo(before.path("cohortSha256"));
        for (Instant invalid : java.util.List.of(HISTORICAL, Instant.parse("2027-01-01T00:00:00Z"))) {
            assertThatThrownBy(() -> publisher.exportTrainingV2("history-v2", invalid, directory.resolve("invalid.csv"), "invalid"))
                    .hasMessageContaining("mature future label window");
        }
        assertThat(directory.resolve("invalid.csv")).doesNotExist();
        jdbc.update("UPDATE pymk_serving_releases SET state='STAGING' WHERE release_id='history-v2'");
        assertThatThrownBy(() -> publisher.exportTrainingV2("history-v2", end, directory.resolve("partial.csv"), "partial"))
                .hasMessageContaining("completed staging");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_training_datasets_v2", Long.class)).isEqualTo(2);
    }

    @Test
    void competingV2PublishersCannotReuseDatasetIdentity() throws Exception {
        assertThat(run(manifest("history-v2", HISTORICAL, 4), 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                start.await();
                try { publisher.exportTrainingV2("history-v2", Instant.parse("2026-01-10T00:00:00Z"), directory.resolve("race-" + i + ".csv"), "race"); return true; }
                catch (IllegalArgumentException ex) { assertThat(ex).hasMessageContaining("Dataset ID is immutable"); return false; }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_training_datasets_v2", Long.class)).isEqualTo(1);
        }
    }

    @Test
    void v2JobRejectsMissingIdentityAndInvalidDatesBeforeExecution() {
        var missing = new JobParametersBuilder().addString("action", "export-v2").addString("releaseId", "history");
        assertThatThrownBy(() -> releaseJob.getJobParametersValidator().validate(missing.toJobParameters()))
                .isInstanceOf(org.springframework.batch.core.JobParametersInvalidException.class);
        var invalidDate = missing.addString("datasetId", "dataset").addString("labelWindowEnd", "not-a-date")
                .addString("outputPath", directory.resolve("invalid.csv").toString());
        assertThatThrownBy(() -> releaseJob.getJobParametersValidator().validate(invalidDate.toJobParameters()))
                .isInstanceOf(org.springframework.batch.core.JobParametersInvalidException.class);
        assertThat(directory.resolve("invalid.csv")).doesNotExist();
    }

    private void event(long from, long to, String type, String at) {
        jdbc.update("INSERT INTO member_events(actor_member_id,target_member_id,type,occurred_at) VALUES(?,?,?,?::timestamptz)", from, to, type, at);
    }

    private org.springframework.batch.core.JobExecution run(Path manifest, long run) throws Exception {
        return jobs.launchJob(new JobParametersBuilder().addString("manifestPath", manifest.toString()).addLong("run.id", run).toJobParameters());
    }

    private long count(String table, String id) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE release_id=?", Long.class, id); }

    private Path manifest(String id, Instant snapshot, int memberCount) throws Exception {
        Path csv = directory.resolve(id + ".csv");
        StringBuilder content = new StringBuilder(EmbeddingRefreshJobConfig.HEADER).append('\n');
        for (String row : new String(resource("vectors.tsv")).lines().limit(memberCount).toList()) {
            String[] columns = row.split("\t");
            content.append(columns[0]).append(",\"").append(columns[1]).append("\",")
                    .append(snapshot).append(',').append(version).append(',').append(HISTORICAL).append('\n');
        }
        Files.writeString(csv, content);
        var json = EmbeddingReleaseManifest.JSON.createObjectNode();
        json.put("schemaVersion",1).put("releaseId",id).put("featureAsOf",snapshot.toString())
                .put("vectorsPath",csv.toString()).put("transformPath",transform.toString())
                .put("vectorsSha256",EmbeddingReleaseManifest.fileHash(csv)).put("transformSha256",EmbeddingReleaseManifest.fileHash(transform));
        json.putObject("candidateLimits").put("graph",2000).put("heuristic",1000).put("embedding",1000);
        Path path = directory.resolve(id + ".manifest.json");
        Files.write(path, EmbeddingReleaseManifest.JSON.writeValueAsBytes(json));
        return path;
    }

    private Path releaseMetadata(String name, String historicalRelease) throws Exception {
        Path csv = directory.resolve(name + ".csv");
        publisher.exportTraining(historicalRelease, Instant.parse("2026-01-10T00:00:00Z"), csv);
        var json = metadata(name);
        json.putObject("embeddingCompatibility").put("transformSha256",EmbeddingReleaseManifest.fileHash(transform))
                .put("embeddingVersion",version).put("featureSchema","pair-v1");
        json.putArray("inputs").addObject().put("sha256",EmbeddingReleaseManifest.fileHash(csv));
        json.putObject("split").put("trainLabelThroughInclusive","2026-01-10T00:00:00Z")
                .put("validationLabelThroughInclusive","2026-01-17T00:00:00Z");
        // Fixed gate report for this Java validation fixture. The end-to-end smoke
        // trains a fresh model on actual registered exports and supplies measured metrics.
        json.putObject("metrics").putObject("validation").put("rows",6).put("rocAuc",0.9);
        Path path = directory.resolve(name + ".metadata.json");
        Files.write(path, EmbeddingReleaseManifest.JSON.writeValueAsBytes(json));
        return path;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode metadata(String version) throws Exception {
        var json = EmbeddingReleaseManifest.JSON.createObjectNode();
        json.put("schemaVersion",1).put("modelVersion",version).put("positiveClass",1);
        json.set("features",EmbeddingReleaseManifest.JSON.valueToTree(LightRankerModel.FEATURE_NAMES));
        var input = json.putObject("inputTensor").put("name","features").put("dtype","float32");
        input.putArray("shape").addNull().add(6);
        json.putObject("onnx").put("sha256",EmbeddingReleaseManifest.fileHash(model));
        return json;
    }

    private byte[] resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/day23/" + name)) { return input.readAllBytes(); }
    }
}
