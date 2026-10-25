package com.ahdyahmed.pymk.api.dto;

import com.ahdyahmed.pymk.orchestrator.RecommendationResult;
import java.util.List;

/** Public PYMK response envelope. */
public record PymkResponse(long memberId, List<PymkRecommendationResponse> recommendations,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        com.ahdyahmed.pymk.reranker.BlendDiversityPolicy.Exposure exposure) {

    public PymkResponse(long memberId, List<PymkRecommendationResponse> recommendations) {
        this(memberId, recommendations, null);
    }

    public static PymkResponse from(RecommendationResult result) {
        return new PymkResponse(
                result.memberId(),
                result.recommendations().stream()
                        .map(PymkRecommendationResponse::from)
                        .toList(), result.exposure());
    }
}
