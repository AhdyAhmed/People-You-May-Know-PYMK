package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import com.ahdyahmed.pymk.domain.vector.VectorLiterals;
import com.ahdyahmed.pymk.featurestore.PairFeatureComputer;
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Restart checkpoints and staged data commit together; no method changes the active pointer. */
@Service
public class EmbeddingReleaseService {
    private final JdbcTemplate jdbc;
    private final SnapshotCandidateRepository candidates;
    private final PairFeatureComputer computer;
    private final FeatureComputationProperties properties;
    private final Clock clock;

    public EmbeddingReleaseService(JdbcTemplate jdbc, SnapshotCandidateRepository candidates,
            PairFeatureComputer computer, FeatureComputationProperties properties, Clock clock) {
        this.jdbc = jdbc; this.candidates = candidates; this.computer = computer;
        this.properties = properties; this.clock = clock;
    }

    @Transactional
    public void prepare(EmbeddingReleaseManifest manifest) throws Exception {
        lockSources();
        if (manifest.snapshot().isAfter(clock.instant())) throw new IllegalArgumentException("Snapshot is in the future");
        properties.validate();
        var limits = manifest.json().path("candidateLimits");
        if (limits.path("graph").asInt() != properties.getGraphCandidateLimit()
                || limits.path("heuristic").asInt() != properties.getHeuristicCandidateLimit()
                || limits.path("embedding").asInt() != properties.getEmbeddingCandidateLimit()) {
            throw new IllegalArgumentException("Manifest candidate limits must match batch configuration");
        }
        jdbc.update("INSERT INTO pymk_embedding_transforms VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                manifest.embeddingVersion(), manifest.transformHash(), Timestamp.from(manifest.fitCutoff()), Files.readAllBytes(manifest.transform()));
        String existingHash = jdbc.queryForObject("SELECT transform_sha256 FROM pymk_embedding_transforms WHERE embedding_version = ?",
                String.class, manifest.embeddingVersion());
        if (!manifest.transformHash().equals(existingHash)) throw new IllegalArgumentException("Embedding version already identifies different bytes");
        jdbc.update("""
                INSERT INTO pymk_serving_releases(release_id, manifest_sha256, manifest, embedding_version,
                    feature_as_of, source_fingerprint, state) VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, 'STAGING')
                ON CONFLICT DO NOTHING
                """, manifest.id(), manifest.manifestHash(), manifest.json().toString(), manifest.embeddingVersion(),
                Timestamp.from(manifest.snapshot()), revision());
        var row = jdbc.queryForMap("SELECT manifest_sha256, state, source_fingerprint FROM pymk_serving_releases WHERE release_id = ? FOR UPDATE", manifest.id());
        if (!manifest.manifestHash().equals(row.get("manifest_sha256"))) throw new IllegalArgumentException("Release ID is immutable");
        if ("STAGING".equals(row.get("state")) && !revision().equals(row.get("source_fingerprint"))) {
            throw new IllegalStateException("Source data changed; stage a new release ID");
        }
    }

    boolean needsImport(String release) {
        return !jdbc.queryForObject("SELECT vectors_loaded FROM pymk_serving_releases WHERE release_id = ?", Boolean.class, release);
    }

    @Transactional
    public void finishImport(EmbeddingReleaseManifest manifest) {
        validateCoverage(manifest);
        jdbc.update("UPDATE pymk_serving_releases SET vectors_loaded = true WHERE release_id = ?", manifest.id());
    }

    void insertVector(EmbeddingReleaseManifest manifest, MemberEmbedding vector) {
        double squared = 0;
        for (float value : vector.getEmbedding()) squared += value * (double) value;
        if (squared != 0 && Math.abs(Math.sqrt(squared) - 1) > 1e-5) throw new IllegalArgumentException("Vector must be zero or unit-normalized");
        if (!vector.getEmbeddingVersion().equals(manifest.embeddingVersion())
                || !vector.getFitCutoff().equals(manifest.fitCutoff()) || !vector.getUpdatedAt().equals(manifest.snapshot())) {
            throw new IllegalArgumentException("Vector provenance differs from manifest");
        }
        jdbc.update("INSERT INTO pymk_release_embeddings VALUES (?, ?, CAST(? AS vector), ?, ?, ?)",
                manifest.id(), vector.getMemberId(), VectorLiterals.toLiteral(vector.getEmbedding()),
                Timestamp.from(vector.getUpdatedAt()), vector.getEmbeddingVersion(), Timestamp.from(vector.getFitCutoff()));
    }

    @Transactional
    public boolean computeNextChunk(EmbeddingReleaseManifest manifest) {
        lockSources();
        var state = jdbc.queryForMap("SELECT state, last_member_id, source_fingerprint FROM pymk_serving_releases WHERE release_id = ? FOR UPDATE", manifest.id());
        if (!"STAGING".equals(state.get("state"))) return true;
        if (!revision().equals(state.get("source_fingerprint"))) throw new IllegalStateException("Source data changed; stage a new release ID");
        long after = ((Number) state.get("last_member_id")).longValue();
        List<Long> members = jdbc.queryForList("SELECT member_id FROM pymk_release_embeddings WHERE release_id = ? AND member_id > ? ORDER BY member_id LIMIT ?",
                Long.class, manifest.id(), after, properties.getChunkSize());
        for (long member : members) {
            MemberEmbedding requester = vector(manifest.id(), member);
            for (long candidate : candidates.findCandidates(member, manifest.snapshot(), manifest.id())) {
                PymkPairFeature f = computer.compute(new PairId(member, candidate), manifest.snapshot(), clock.instant(),
                        requester, vector(manifest.id(), candidate));
                jdbc.update("INSERT INTO pymk_release_features VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        manifest.id(), member, candidate, f.getMutualConnectionCount(), f.isSameCompany(),
                        f.isSameSchool(), f.isSameGeo(), f.getEmbeddingCosineSim(), f.getProfileViewCountLast30d(),
                        Timestamp.from(f.getFeatureAsOf()), Timestamp.from(f.getComputedAt()));
            }
            jdbc.update("UPDATE pymk_serving_releases SET last_member_id = ? WHERE release_id = ?", member, manifest.id());
        }
        if (members.isEmpty()) {
            validateCoverage(manifest);
            jdbc.update("UPDATE pymk_serving_releases SET state = 'STAGED' WHERE release_id = ?", manifest.id());
            return true;
        }
        return false;
    }

    void validateCoverage(EmbeddingReleaseManifest m) {
        Long eligible = jdbc.queryForObject("SELECT count(*) FROM members WHERE created_at <= ?", Long.class, Timestamp.from(m.snapshot()));
        Long count = jdbc.queryForObject("SELECT count(*) FROM pymk_release_embeddings WHERE release_id = ?", Long.class, m.id());
        Long future = jdbc.queryForObject("SELECT count(*) FROM pymk_release_embeddings e JOIN members m ON m.id=e.member_id WHERE e.release_id=? AND m.created_at > ?",
                Long.class, m.id(), Timestamp.from(m.snapshot()));
        if (eligible == 0 || !eligible.equals(count) || future != 0) throw new IllegalStateException("Embedding generation does not cover exactly the snapshot members");
    }

    MemberEmbedding vector(String release, long member) {
        return jdbc.queryForObject("SELECT embedding::text, updated_at, embedding_version, fit_cutoff FROM pymk_release_embeddings WHERE release_id=? AND member_id=?",
                (rs, n) -> {
                    try { return new MemberEmbedding(member, EmbeddingReleaseManifest.JSON.readValue(rs.getString(1), float[].class),
                            rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getTimestamp(4).toInstant()); }
                    catch (java.io.IOException ex) { throw new IllegalStateException("Invalid staged vector", ex); }
                }, release, member);
    }

    void lockSources() { jdbc.execute("LOCK TABLE members, connections, member_events IN SHARE MODE"); }
    String revision() { return jdbc.queryForObject("SELECT revision::text FROM pymk_source_revision", String.class); }
}
