package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.ahdyahmed.pymk.lightranker.OnnxLightRankerModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validation and pointer publication share a transaction. Model bytes survive local file removal. */
@Service
public class ServingReleaseService {
    private final JdbcTemplate jdbc;
    private final EmbeddingReleaseService staging;
    private final Clock clock;
    public ServingReleaseService(JdbcTemplate jdbc, EmbeddingReleaseService staging, Clock clock) {
        this.jdbc = jdbc; this.staging = staging; this.clock = clock;
    }

    @Transactional
    public void activate(String id, long expectedEpoch, Path modelPath, Path metadataPath,
                         Path legacyModel, Path legacyMetadata) throws Exception {
        lock(expectedEpoch);
        staging.lockSources();
        var release = jdbc.queryForMap("SELECT * FROM pymk_serving_releases WHERE release_id = ? FOR UPDATE", id);
        if (!"STAGED".equals(release.get("state")) && !"READY".equals(release.get("state"))) throw new IllegalStateException("Release is not fully staged");
        if (!staging.revision().equals(release.get("source_fingerprint"))) throw new IllegalStateException("Source changed since staging; rebuild before activation");
        Instant snapshot = ((Timestamp) release.get("feature_as_of")).toInstant();
        Long newerMembers = jdbc.queryForObject("SELECT count(*) FROM members WHERE created_at > ?", Long.class, Timestamp.from(snapshot));
        Long newerEdges = jdbc.queryForObject("SELECT count(*) FROM connections WHERE connected_at > ?", Long.class, Timestamp.from(snapshot));
        if (newerMembers != 0 || newerEdges != 0) throw new IllegalStateException("Serving snapshot does not cover the live member/graph cohort");

        byte[] bytes = Files.readAllBytes(modelPath);
        byte[] metadataBytes = Files.readAllBytes(metadataPath);
        JsonNode metadata = EmbeddingReleaseManifest.JSON.readTree(metadataBytes);
        String hash = jdbc.queryForObject("SELECT transform_sha256 FROM pymk_embedding_transforms WHERE embedding_version = ?", String.class, release.get("embedding_version"));
        var compatibility = metadata.path("embeddingCompatibility");
        if (!hash.equals(compatibility.path("transformSha256").asText())
                || !release.get("embedding_version").equals(compatibility.path("embeddingVersion").asText())
                || !"pair-v1".equals(compatibility.path("featureSchema").asText())) {
            throw new IllegalArgumentException("Model was not trained on this frozen embedding transform");
        }
        var validation = metadata.path("metrics").path("validation");
        JsonNode manifest = EmbeddingReleaseManifest.JSON.readTree(release.get("manifest").toString());
        double minimum = manifest.path("minValidationAuc").asDouble(0.5);
        double auc = validation.path("rocAuc").asDouble(Double.NaN);
        if (!Double.isFinite(minimum) || minimum < 0.5 || minimum > 1
                || !Double.isFinite(auc) || auc < minimum || auc > 1 || validation.path("rows").asInt() < 2) {
            throw new IllegalArgumentException("Validation AUC does not satisfy the release quality gate");
        }
        if (Instant.parse(metadata.path("split").path("trainLabelThroughInclusive").asText()).isAfter(snapshot)
                || Instant.parse(metadata.path("split").path("validationLabelThroughInclusive").asText()).isAfter(snapshot)) {
            throw new IllegalArgumentException("Model selection uses outcomes newer than the serving snapshot");
        }
        if (!metadata.path("inputs").isArray() || metadata.path("inputs").isEmpty()) throw new IllegalArgumentException("Model lacks training provenance");
        for (var input : metadata.path("inputs")) {
            Long matches = jdbc.queryForObject("""
                    SELECT count(*) FROM pymk_release_exports e
                    JOIN pymk_serving_releases r USING (release_id)
                    JOIN pymk_embedding_transforms t USING (embedding_version)
                    WHERE e.csv_sha256 = ? AND t.transform_sha256 = ? AND e.label_window_end <= ?
                    """, Long.class, input.path("sha256").asText(), hash, Timestamp.from(snapshot));
            if (matches != 1) throw new IllegalArgumentException("Model input is not a registered compatible mature release export");
        }

        try (var model = OnnxLightRankerModel.load(bytes, metadata)) {
            var samples = jdbc.query("""
                    SELECT mutual_connection_count, same_company, same_school, same_geo,
                           embedding_cosine_sim, profile_view_count_last_30d
                    FROM pymk_release_features WHERE release_id = ? ORDER BY member_id, candidate_id LIMIT 100
                    """, (rs, n) -> new float[]{rs.getFloat(1), rs.getBoolean(2) ? 1 : 0, rs.getBoolean(3) ? 1 : 0,
                            rs.getBoolean(4) ? 1 : 0, rs.getFloat(5), rs.getFloat(6)}, id);
            model.score(samples.isEmpty() ? new float[][]{new float[6]} : samples.toArray(float[][]::new));
            if ("READY".equals(release.get("state"))
                    && (!java.util.Arrays.equals(bytes, (byte[]) release.get("model_bytes"))
                        || !java.util.Arrays.equals(metadataBytes, (byte[]) release.get("model_metadata")))) {
                throw new IllegalStateException("Published release/model bytes are immutable");
            }
            Long reused = jdbc.queryForObject("SELECT count(*) FROM pymk_serving_releases WHERE model_version = ? AND model_bytes <> ?",
                    Long.class, model.modelVersion(), bytes);
            if (reused != 0) throw new IllegalArgumentException("Model version already identifies different bytes");
            if (jdbc.queryForObject("SELECT count(*) FROM pymk_legacy_release_model", Long.class) == 0) {
                byte[] baseline = Files.readAllBytes(legacyModel);
                byte[] baselineMetadata = Files.readAllBytes(legacyMetadata);
                try (var old = OnnxLightRankerModel.load(baseline, EmbeddingReleaseManifest.JSON.readTree(baselineMetadata))) {
                    old.score(new float[][]{new float[6]});
                    jdbc.update("INSERT INTO pymk_legacy_release_model VALUES (true, ?, ?, ?)", old.modelVersion(), baseline, baselineMetadata);
                }
            }
            jdbc.update("UPDATE pymk_serving_releases SET state='READY', model_version=?, model_bytes=?, model_metadata=? WHERE release_id=?",
                    model.modelVersion(), bytes, metadataBytes, id);
            jdbc.update("UPDATE pymk_active_release SET release_id=?, epoch=epoch+1", id);
        }
    }

    @Transactional
    public void rollback(String id, long expectedEpoch) {
        lock(expectedEpoch);
        if ("legacy".equals(id)) {
            if (jdbc.queryForObject("SELECT count(*) FROM pymk_legacy_release_model", Long.class) != 1) throw new IllegalStateException("Legacy rollback model was not captured");
            jdbc.update("UPDATE pymk_active_release SET release_id=NULL, epoch=epoch+1");
        } else {
            if (jdbc.queryForObject("SELECT count(*) FROM pymk_serving_releases WHERE release_id=? AND state='READY'", Long.class, id) != 1) throw new IllegalStateException("Rollback requires a previously validated READY release");
            jdbc.update("UPDATE pymk_active_release SET release_id=?, epoch=epoch+1", id);
        }
    }

    private void lock(long epoch) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ServingReleaseStore.LOCK_ID);
        long actual = jdbc.queryForObject("SELECT epoch FROM pymk_active_release FOR UPDATE", Long.class);
        if (epoch != actual) throw new IllegalStateException("Stale expectedEpoch; active release changed");
    }

    @Transactional
    public void exportTraining(String id, Instant labelEnd, Path output) throws Exception {
        staging.lockSources();
        var release = jdbc.queryForMap("""
                SELECT r.*, t.transform_sha256 FROM pymk_serving_releases r
                JOIN pymk_embedding_transforms t USING (embedding_version) WHERE release_id=?
                """, id);
        Instant snapshot = ((Timestamp) release.get("feature_as_of")).toInstant();
        if ("STAGING".equals(release.get("state")) || !labelEnd.isAfter(snapshot) || labelEnd.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Export requires completed staging and a mature future label window");
        }
        output = output.toAbsolutePath().normalize();
        Path sidecar = Path.of(output + ".provenance.json");
        if (Files.exists(output) || Files.exists(sidecar)) throw new IllegalArgumentException("Release exports are immutable; choose a new output path");
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".release-export-", ".tmp");
        long[] count = {0};
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                writer.write(TrainingExample.CSV_HEADER); writer.newLine();
                jdbc.query(con -> {
                    var statement = con.prepareStatement("""
                            SELECT f.*, CASE WHEN EXISTS (SELECT 1 FROM member_events e
                                WHERE e.actor_member_id=f.member_id AND e.target_member_id=f.candidate_id
                                AND e.type='INVITE_ACCEPTED' AND e.occurred_at > f.feature_as_of AND e.occurred_at <= ?)
                                THEN 1 ELSE 0 END AS label FROM pymk_release_features f
                            WHERE f.release_id=? ORDER BY member_id, candidate_id
                            """);
                    statement.setTimestamp(1, Timestamp.from(labelEnd)); statement.setString(2, id); statement.setFetchSize(1000);
                    return statement;
                }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    var row = new TrainingExample(rs.getLong("member_id"), rs.getLong("candidate_id"), rs.getInt("mutual_connection_count"),
                            rs.getBoolean("same_company"), rs.getBoolean("same_school"), rs.getBoolean("same_geo"),
                            rs.getDouble("embedding_cosine_sim"), rs.getInt("profile_view_count_last_30d"), snapshot.minusSeconds(30L*86400),
                            snapshot, labelEnd, rs.getInt("label"));
                    try { writer.write(row.toCsv()); writer.newLine(); count[0]++; }
                    catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
                });
            }
            if (count[0] == 0) throw new IllegalStateException("No staged feature rows to export");
            String hash = EmbeddingReleaseManifest.fileHash(temporary);
            var provenance = EmbeddingReleaseManifest.JSON.createObjectNode();
            provenance.put("schemaVersion", 1).put("releaseId", id).put("csvSha256", hash)
                    .put("transformSha256", release.get("transform_sha256").toString())
                    .put("embeddingVersion", release.get("embedding_version").toString())
                    .put("featureAsOf", snapshot.toString()).put("labelWindowEnd", labelEnd.toString());
            Files.createLink(output, temporary);
            Path sideTemporary = Files.createTempFile(output.getParent(), ".release-provenance-", ".tmp");
            try { Files.write(sideTemporary, EmbeddingReleaseManifest.JSON.writeValueAsBytes(provenance)); Files.createLink(sidecar, sideTemporary); }
            finally { Files.deleteIfExists(sideTemporary); }
            jdbc.update("INSERT INTO pymk_release_exports(csv_sha256, release_id, label_window_end) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    hash, id, Timestamp.from(labelEnd));
        } finally { Files.deleteIfExists(temporary); }
    }
}
