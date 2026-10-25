package com.ahdyahmed.pymk.orchestrator;

import java.util.List;

/** Recommendation response before transport-specific serialization. */
public record RecommendationResult(long memberId, List<Recommendation> recommendations,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.ahdyahmed.pymk.reranker.BlendDiversityPolicy.Exposure exposure) {

    public RecommendationResult(long memberId, List<Recommendation> recommendations) {
        this(memberId, recommendations, null);
    }

    public RecommendationResult {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        recommendations = List.copyOf(recommendations);
    }
}
