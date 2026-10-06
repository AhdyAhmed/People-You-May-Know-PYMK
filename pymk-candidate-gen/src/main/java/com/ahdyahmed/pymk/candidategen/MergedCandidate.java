package com.ahdyahmed.pymk.candidategen;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A de-duplicated L0 candidate retaining every contributing source hit. */
public record MergedCandidate(
        long candidateId,
        double fusionScore,
        List<CandidateHit> sourceHits) {

    public MergedCandidate {
        if (candidateId <= 0) {
            throw new IllegalArgumentException("candidateId must be positive");
        }
        if (!Double.isFinite(fusionScore) || fusionScore <= 0) {
            throw new IllegalArgumentException("fusionScore must be finite and positive");
        }
        Objects.requireNonNull(sourceHits, "sourceHits must not be null");
        if (sourceHits.isEmpty()) {
            throw new IllegalArgumentException("sourceHits must not be empty");
        }
        Set<CandidateSourceType> sources = EnumSet.noneOf(CandidateSourceType.class);
        for (CandidateHit hit : sourceHits) {
            Objects.requireNonNull(hit, "sourceHits must not contain null");
            if (hit.candidateId() != candidateId) {
                throw new IllegalArgumentException("Every source hit must belong to candidateId");
            }
            if (!sources.add(hit.source())) {
                throw new IllegalArgumentException("Duplicate source hit: " + hit.source());
            }
        }
        sourceHits = List.copyOf(sourceHits);
    }
}
