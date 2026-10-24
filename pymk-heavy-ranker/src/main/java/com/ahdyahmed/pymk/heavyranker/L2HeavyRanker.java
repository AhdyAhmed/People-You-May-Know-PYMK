package com.ahdyahmed.pymk.heavyranker;

import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** One pinned release, one batched feature read and one model call for all L1 survivors. */
public class L2HeavyRanker {
    private final ServingReleaseStore releases;
    private final JdbcTemplate jdbc;
    private final PymkPairFeatureRepository features;
    private final HeavyRankerModel model;
    private final int maxCandidates;

    public L2HeavyRanker(ServingReleaseStore releases, JdbcTemplate jdbc, PymkPairFeatureRepository features,
                         HeavyRankerModel model, int maxCandidates) {
        this.releases = Objects.requireNonNull(releases); this.jdbc = Objects.requireNonNull(jdbc);
        this.features = Objects.requireNonNull(features); this.model = Objects.requireNonNull(model);
        if (maxCandidates < 1 || maxCandidates > 10000) throw new IllegalArgumentException("L2 batch limit must be 1..10000");
        this.maxCandidates = maxCandidates;
    }

    @Transactional(readOnly = true)
    public void verifyRelease() {
        try (var pin = releases.pin()) { validatePinnedRelease(releases.current()); }
        catch (DataAccessException ex) { throw new HeavyRankerException("L2 release read failed", ex); }
    }

    private java.time.Instant validatePinnedRelease(ServingReleaseStore.ActiveRelease release) {
        if (!release.managed()) throw new HeavyRankerException("L2 requires a managed profile-embedding release");
        var context = jdbc.queryForMap("""
                SELECT r.feature_as_of, r.embedding_version, t.transform_sha256, t.fit_cutoff
                FROM pymk_serving_releases r JOIN pymk_embedding_transforms t USING (embedding_version)
                WHERE r.release_id=? AND r.state='READY'
                """, release.id());
        var snapshot = ((Timestamp) context.get("feature_as_of")).toInstant();
        var contract = model.compatibility();
        if (contract == null || !"pair-v1".equals(contract.featureSchema())
                || !Objects.equals(context.get("embedding_version"), contract.embeddingVersion())
                || !Objects.equals(context.get("transform_sha256"), contract.transformSha256())
                || !((Timestamp) context.get("fit_cutoff")).toInstant().equals(contract.fitCutoff())
                || contract.selectionCutoff() == null || contract.selectionCutoff().isAfter(snapshot)) {
            throw new HeavyRankerException("L2 model is incompatible with the pinned embedding/feature release");
        }
        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<HeavyScoredCandidate> score(long memberId, List<LightRankedCandidate> survivors) {
        if (memberId <= 0) throw new IllegalArgumentException("memberId must be positive");
        Objects.requireNonNull(survivors, "survivors");
        if (survivors.size() > maxCandidates) throw new IllegalArgumentException("L2 batch exceeds configured limit; no truncation is allowed");
        if (survivors.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>();
        Set<Long> unique = new HashSet<>();
        for (var candidate : survivors) {
            Objects.requireNonNull(candidate, "null L1 survivor");
            if (candidate.candidateId() == memberId || !unique.add(candidate.candidateId())) throw new IllegalArgumentException("Self or duplicate L1 survivor");
            ids.add(candidate.candidateId());
        }
        try (var pin = releases.pin()) {
            var release = releases.current();
            var snapshot = validatePinnedRelease(release);
            for (var survivor : survivors) {
                if (!snapshot.equals(survivor.featureAsOf()) || !Objects.equals(release.modelVersion(), survivor.modelVersion())) {
                    throw new HeavyRankerException("L1 survivors do not belong to the pinned release snapshot/model");
                }
            }
            Map<Long, PymkPairFeature> byId = new HashMap<>();
            for (var row : features.findForCandidates(memberId, ids)) {
                if (row == null || row.getId().getMemberId() != memberId || !unique.contains(row.getId().getCandidateId())
                        || !snapshot.equals(row.getFeatureAsOf()) || byId.put(row.getId().getCandidateId(), row) != null) {
                    throw new HeavyRankerException("Invalid, foreign, duplicate or mixed-snapshot L2 feature row");
                }
            }
            if (byId.size() != ids.size()) throw new HeavyRankerException("Missing materialized L2 features");
            float[][] matrix = new float[ids.size()][6];
            for (int i = 0; i < ids.size(); i++) {
                var f = byId.get(ids.get(i));
                matrix[i] = new float[]{f.getMutualConnectionCount(), f.isSameCompany() ? 1 : 0, f.isSameSchool() ? 1 : 0,
                        f.isSameGeo() ? 1 : 0, (float) f.getEmbeddingCosineSim(), f.getProfileViewCountLast30d()};
            }
            var scores = model.score(matrix);
            if (scores == null || scores.size() != ids.size()) throw new HeavyRankerException("L2 returned an incomplete batch");
            String version = model.modelVersion();
            if (version == null || version.isBlank()) throw new HeavyRankerException("L2 model version is missing");
            var result = new ArrayList<HeavyScoredCandidate>();
            for (int i = 0; i < scores.size(); i++) {
                if (scores.get(i) == null) throw new HeavyRankerException("L2 returned a null score");
                var valid = new HeavyRankerModel.Probabilities(scores.get(i).inviteSent(), scores.get(i).inviteAccepted());
                result.add(new HeavyScoredCandidate(survivors.get(i), valid, release.id(), release.epoch(), version));
            }
            return List.copyOf(result);
        } catch (DataAccessException ex) { throw new HeavyRankerException("L2 feature/release read failed", ex); }
    }
}
