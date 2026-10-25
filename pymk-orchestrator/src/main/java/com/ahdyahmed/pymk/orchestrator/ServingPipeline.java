package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.heavyranker.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable process-level mode. Restart to promote/roll back; never silently fall back on failure. */
@Component
public class ServingPipeline {
    private final L2HeavyRanker heavy;
    private final String identity;
    private final String mode;
    private final com.ahdyahmed.pymk.reranker.BlendDiversityPolicy policy;
    private final int poolLimit;

    @Autowired
    public ServingPipeline(ObjectProvider<L2HeavyRanker> ranker, ObjectProvider<HeavyRankerModel> model,
            @Value("${pymk.serving.ranker:L1}") String mode,
            @Value("${pymk.light-ranker.max-candidates:500}") int l1Limit,
            @Value("${pymk.heavy-ranker.max-candidates:500}") int l2Limit,
            @Value("${pymk.reranker.sent-weight:0.5}") double sentWeight,
            @Value("${pymk.reranker.accepted-weight:0.5}") double acceptedWeight,
            @Value("${pymk.reranker.employer-cap-fraction:0.4}") double capFraction) {
        this(mode, !"L1".equals(mode) ? ranker.getIfAvailable() : null,
                !"L1".equals(mode) ? model.getIfAvailable() : null, l1Limit, l2Limit,
                new com.ahdyahmed.pymk.reranker.BlendDiversityPolicy(sentWeight, acceptedWeight, capFraction));
    }

    public ServingPipeline(String mode, L2HeavyRanker ranker, HeavyRankerModel model, int l1Limit, int l2Limit) {
        this(mode, ranker, model, l1Limit, l2Limit, new com.ahdyahmed.pymk.reranker.BlendDiversityPolicy(.5, .5, .4));
    }

    public ServingPipeline(String mode, L2HeavyRanker ranker, HeavyRankerModel model, int l1Limit, int l2Limit,
            com.ahdyahmed.pymk.reranker.BlendDiversityPolicy policy) {
        if (!java.util.Set.of("L1", "L2", "RERANK").contains(mode)) throw new IllegalArgumentException("Serving ranker must be L1, L2 or RERANK");
        if (l1Limit < 1) throw new IllegalArgumentException("L1 survivor limit must be positive");
        this.mode = mode;
        this.poolLimit = l1Limit;
        this.policy = java.util.Objects.requireNonNull(policy);
        if (!"L1".equals(mode)) {
            if (ranker == null || model == null) throw new IllegalStateException("L2 serving requires pymk.heavy-ranker.enabled=true and valid artifacts");
            if (l2Limit < l1Limit) throw new IllegalStateException("L2 batch capacity must cover all L1 survivors");
            String artifact = model.artifactIdentity();
            if (artifact == null || !artifact.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid L2 artifact identity");
            heavy = ranker;
            identity = (usesReranker() ? policy.identity() : "day28-l2-accepted-v1") + "-" + l1Limit + "-" + artifact;
        } else {
            heavy = null;
            identity = "day28-l1-v1-" + l1Limit;
        }
    }
    public boolean usesHeavyRanker() { return heavy != null; }
    public L2HeavyRanker heavyRanker() { return heavy; }
    public String identity() { return identity; }
    public String mode() { return mode; }
    public boolean usesReranker() { return "RERANK".equals(mode); }
    public com.ahdyahmed.pymk.reranker.BlendDiversityPolicy policy() { return policy; }
    public int poolLimit() { return poolLimit; }
}
