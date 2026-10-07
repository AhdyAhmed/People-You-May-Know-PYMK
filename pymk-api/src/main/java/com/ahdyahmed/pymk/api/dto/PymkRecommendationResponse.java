package com.ahdyahmed.pymk.api.dto;

import com.ahdyahmed.pymk.orchestrator.Recommendation;
import java.util.List;

/** Public representation of one recommended member. */
public record PymkRecommendationResponse(
        long candidateId,
        double score,
        long mutualConnectionCount,
        List<String> reasons) {

    public static PymkRecommendationResponse from(Recommendation recommendation) {
        return new PymkRecommendationResponse(
                recommendation.candidateId(),
                recommendation.score(),
                recommendation.mutualConnectionCount(),
                recommendation.reasons());
    }
}
