package com.ahdyahmed.pymk.candidategen;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** L0 candidates reachable through shortest two-hop or three-hop graph paths. */
@Service
public class GraphWalkCandidateSource implements CandidateSource {

    private final GraphWalkCandidateRepository graph;
    private final CandidateEligibilityPolicy eligibility;

    public GraphWalkCandidateSource(
            GraphWalkCandidateRepository graph,
            CandidateEligibilityPolicy eligibility) {
        this.graph = graph;
        this.eligibility = eligibility;
    }

    @Override
    public CandidateSourceType type() {
        return CandidateSourceType.GRAPH_WALK;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateHit> generate(long memberId, int limit) {
        CandidateSource.validateRequest(memberId, limit);
        List<CandidateHit> hits = graph.findCandidates(memberId, limit).stream()
                .map(candidate -> new CandidateHit(
                        candidate.memberId(),
                        type(),
                        graphScore(candidate.hopDistance(), candidate.pathCount()),
                        Map.of(
                                "hopDistance", Integer.toString(candidate.hopDistance()),
                                "shortestPathCount", Long.toString(candidate.pathCount()))))
                .toList();
        return eligibility.filter(memberId, hits);
    }

    private static double graphScore(int hopDistance, long pathCount) {
        double pathStrength = (double) pathCount / (pathCount + 1.0);
        return pathStrength / (hopDistance - 1.0);
    }
}
