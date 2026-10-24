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

    @Autowired
    public ServingPipeline(ObjectProvider<L2HeavyRanker> ranker, ObjectProvider<HeavyRankerModel> model,
            @Value("${pymk.serving.ranker:L1}") String mode,
            @Value("${pymk.light-ranker.max-candidates:500}") int l1Limit,
            @Value("${pymk.heavy-ranker.max-candidates:500}") int l2Limit) {
        this(mode, "L2".equals(mode) ? ranker.getIfAvailable() : null,
                "L2".equals(mode) ? model.getIfAvailable() : null, l1Limit, l2Limit);
    }

    public ServingPipeline(String mode, L2HeavyRanker ranker, HeavyRankerModel model, int l1Limit, int l2Limit) {
        if (!"L1".equals(mode) && !"L2".equals(mode)) throw new IllegalArgumentException("Serving ranker must be L1 or L2");
        if (l1Limit < 1) throw new IllegalArgumentException("L1 survivor limit must be positive");
        this.mode = mode;
        if ("L2".equals(mode)) {
            if (ranker == null || model == null) throw new IllegalStateException("L2 serving requires pymk.heavy-ranker.enabled=true and valid artifacts");
            if (l2Limit < l1Limit) throw new IllegalStateException("L2 batch capacity must cover all L1 survivors");
            String artifact = model.artifactIdentity();
            if (artifact == null || !artifact.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid L2 artifact identity");
            heavy = ranker;
            identity = "day28-l2-accepted-v1-" + l1Limit + "-" + artifact;
        } else {
            heavy = null;
            identity = "day28-l1-v1-" + l1Limit;
        }
    }
    public boolean usesHeavyRanker() { return heavy != null; }
    public L2HeavyRanker heavyRanker() { return heavy; }
    public String identity() { return identity; }
    public String mode() { return mode; }
}
