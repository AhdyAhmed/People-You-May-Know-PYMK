package com.ahdyahmed.pymk.candidategen;

import java.util.Map;
import java.util.Objects;

/**
 * A source-local candidate with enough provenance for later merging,
 * explanation, and ranking. Scores are meaningful within their source; L1 is
 * responsible for calibration across heterogeneous sources.
 */
public record CandidateHit(
        long candidateId,
        CandidateSourceType source,
        double sourceScore,
        Map<String, String> metadata) {

    public CandidateHit {
        if (candidateId <= 0) {
            throw new IllegalArgumentException("candidateId must be positive");
        }
        Objects.requireNonNull(source, "source must not be null");
        if (!Double.isFinite(sourceScore)) {
            throw new IllegalArgumentException("sourceScore must be finite");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
