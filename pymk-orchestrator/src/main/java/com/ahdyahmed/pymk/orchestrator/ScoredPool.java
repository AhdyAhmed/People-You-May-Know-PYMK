package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.reranker.BlendDiversityPolicy;
import java.util.*;

/** Cacheable, unselected pool: never truncate to the public 100-result page before diversity. */
public record ScoredPool(long memberId, List<Row> rows) {
    public record Row(Recommendation recommendation, String employer) {
        public Row {
            Objects.requireNonNull(recommendation);
            employer = BlendDiversityPolicy.employerGroup(employer);
        }
    }
    public ScoredPool {
        if (memberId <= 0) throw new IllegalArgumentException("Member ID must be positive");
        rows = List.copyOf(rows);
        var ids = new HashSet<Long>();
        for (var row : rows) {
            long id = row.recommendation().candidateId();
            if (id == memberId || !ids.add(id)) throw new IllegalArgumentException("Self or duplicate pool candidate");
        }
    }
    public RecommendationResult select(BlendDiversityPolicy policy, int limit) {
        var selection = policy.select(rows.stream().map(row -> new BlendDiversityPolicy.Candidate(
                row.recommendation().candidateId(), row.recommendation().score(), row.employer())).toList(), limit);
        var byId = new HashMap<Long, Recommendation>();
        rows.forEach(row -> byId.put(row.recommendation().candidateId(), row.recommendation()));
        return new RecommendationResult(memberId, selection.candidateIds().stream().map(byId::get).toList(), selection.exposure());
    }
}
