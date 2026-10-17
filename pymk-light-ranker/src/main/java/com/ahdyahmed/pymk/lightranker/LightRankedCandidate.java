package com.ahdyahmed.pymk.lightranker;

import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import java.time.Instant;
import java.util.Objects;

/** An L0 candidate enriched with its L1 positive-class probability and feature snapshot. */
public record LightRankedCandidate(
        MergedCandidate candidate,
        double score,
        int mutualConnectionCount,
        Instant featureAsOf,
        String modelVersion) {

    public LightRankedCandidate {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be finite and between 0 and 1");
        }
        if (mutualConnectionCount < 0) {
            throw new IllegalArgumentException("mutualConnectionCount must not be negative");
        }
        Objects.requireNonNull(featureAsOf, "featureAsOf must not be null");
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
    }

    public long candidateId() {
        return candidate.candidateId();
    }
}
