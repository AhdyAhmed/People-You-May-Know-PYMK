package com.ahdyahmed.pymk.orchestrator;

import java.util.List;

/** Recommendation response before transport-specific serialization. */
public record RecommendationResult(long memberId, List<Recommendation> recommendations) {

    public RecommendationResult {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        recommendations = List.copyOf(recommendations);
    }
}
