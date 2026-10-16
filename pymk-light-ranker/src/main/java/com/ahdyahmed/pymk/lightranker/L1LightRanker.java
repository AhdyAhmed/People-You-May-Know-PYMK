package com.ahdyahmed.pymk.lightranker;

import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/** Narrows an L0 candidate batch using one feature read and one model invocation. */
public class L1LightRanker {

    private static final Comparator<LightRankedCandidate> RANK_ORDER =
            Comparator.comparingDouble(LightRankedCandidate::score).reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                            (LightRankedCandidate ranked) ->
                                                    ranked.candidate().fusionScore())
                                    .reversed())
                    .thenComparingLong(LightRankedCandidate::candidateId);

    private final PymkPairFeatureRepository featureRepository;
    private final LightRankerModel model;
    private final int maxCandidates;

    public L1LightRanker(
            PymkPairFeatureRepository featureRepository,
            LightRankerModel model,
            int maxCandidates) {
        this.featureRepository = Objects.requireNonNull(featureRepository);
        this.model = Objects.requireNonNull(model);
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be positive");
        }
        this.maxCandidates = maxCandidates;
    }

    @Transactional(readOnly = true)
    public List<LightRankedCandidate> rank(long memberId, List<MergedCandidate> candidates) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = new ArrayList<>(candidates.size());
        Set<Long> uniqueIds = new HashSet<>();
        for (MergedCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates must not contain null");
            if (candidate.candidateId() == memberId) {
                throw new IllegalArgumentException("candidates must not contain the requesting member");
            }
            if (!uniqueIds.add(candidate.candidateId())) {
                throw new IllegalArgumentException(
                        "duplicate candidateId: " + candidate.candidateId());
            }
            candidateIds.add(candidate.candidateId());
        }

        List<PymkPairFeature> rows = featureRepository.findForCandidates(memberId, candidateIds);
        Map<Long, PymkPairFeature> featuresByCandidate = new HashMap<>(rows.size());
        for (PymkPairFeature row : rows) {
            PymkPairFeature previous = featuresByCandidate.put(row.getId().getCandidateId(), row);
            if (previous != null) {
                throw new LightRankerException("Feature store returned a duplicate pair row");
            }
        }
        List<Long> missing = candidateIds.stream()
                .filter(candidateId -> !featuresByCandidate.containsKey(candidateId))
                .toList();
        if (!missing.isEmpty()) {
            throw new LightRankerException(
                    "Missing materialized features for candidate IDs: " + summarize(missing));
        }

        Instant snapshot = rows.getFirst().getFeatureAsOf();
        if (rows.stream().anyMatch(row -> !snapshot.equals(row.getFeatureAsOf()))) {
            throw new LightRankerException("Feature batch mixes multiple feature_as_of snapshots");
        }

        float[][] matrix = new float[candidates.size()][LightRankerModel.FEATURE_NAMES.size()];
        for (int index = 0; index < candidates.size(); index++) {
            matrix[index] = toVector(featuresByCandidate.get(candidates.get(index).candidateId()));
        }
        float[] scores = model.score(matrix);
        if (scores == null || scores.length != candidates.size()) {
            throw new LightRankerException("L1 model returned the wrong number of scores");
        }

        List<LightRankedCandidate> ranked = new ArrayList<>(scores.length);
        for (int index = 0; index < scores.length; index++) {
            ranked.add(new LightRankedCandidate(
                    candidates.get(index), scores[index], snapshot, model.modelVersion()));
        }
        ranked.sort(RANK_ORDER);
        return List.copyOf(ranked.subList(0, Math.min(maxCandidates, ranked.size())));
    }

    private static float[] toVector(PymkPairFeature feature) {
        return new float[] {
            feature.getMutualConnectionCount(),
            feature.isSameCompany() ? 1.0f : 0.0f,
            feature.isSameSchool() ? 1.0f : 0.0f,
            feature.isSameGeo() ? 1.0f : 0.0f,
            (float) feature.getEmbeddingCosineSim(),
            feature.getProfileViewCountLast30d()
        };
    }

    private static String summarize(List<Long> ids) {
        int shown = Math.min(ids.size(), 10);
        String summary = ids.subList(0, shown).toString();
        return ids.size() == shown ? summary : summary + " (and " + (ids.size() - shown) + " more)";
    }
}
