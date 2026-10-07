package com.ahdyahmed.pymk.api.dto;

import com.ahdyahmed.pymk.orchestrator.RecommendationResult;
import java.util.List;

/** Public PYMK response envelope. */
public record PymkResponse(long memberId, List<PymkRecommendationResponse> recommendations) {

    public static PymkResponse from(RecommendationResult result) {
        return new PymkResponse(
                result.memberId(),
                result.recommendations().stream()
                        .map(PymkRecommendationResponse::from)
                        .toList());
    }
}
