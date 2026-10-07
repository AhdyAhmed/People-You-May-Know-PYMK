package com.ahdyahmed.pymk.api.service;

import com.ahdyahmed.pymk.api.dto.PymkResponse;
import com.ahdyahmed.pymk.api.error.MemberNotFoundException;
import com.ahdyahmed.pymk.orchestrator.RecommendationOrchestrator;
import org.springframework.stereotype.Service;

/** Adapts the internal recommendation contract to the public API contract. */
@Service
public class PymkService {

    private final RecommendationOrchestrator orchestrator;

    public PymkService(RecommendationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public PymkResponse getRecommendations(long memberId, int limit) {
        return orchestrator.getRecommendations(memberId, limit)
                .map(PymkResponse::from)
                .orElseThrow(() -> new MemberNotFoundException(memberId));
    }
}
