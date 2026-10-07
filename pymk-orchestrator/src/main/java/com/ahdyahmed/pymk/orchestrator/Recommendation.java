package com.ahdyahmed.pymk.orchestrator;

import java.util.List;

/** A stable recommendation contract shared by orchestration and API layers. */
public record Recommendation(
        long candidateId,
        double score,
        long mutualConnectionCount,
        List<String> reasons) {

    public Recommendation {
        if (candidateId <= 0) {
            throw new IllegalArgumentException("candidateId must be positive");
        }
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
        if (mutualConnectionCount < 0) {
            throw new IllegalArgumentException("mutualConnectionCount must not be negative");
        }
        reasons = List.copyOf(reasons);
    }
}
