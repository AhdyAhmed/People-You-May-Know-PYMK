package com.ahdyahmed.pymk.heavyranker;

import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import java.util.Objects;

/** Both heads retain the original L1 order/provenance; blending is a later milestone. */
public record HeavyScoredCandidate(LightRankedCandidate l1, HeavyRankerModel.Probabilities probabilities,
                                   String releaseId, long releaseEpoch, String modelVersion) {
    public HeavyScoredCandidate {
        Objects.requireNonNull(l1); Objects.requireNonNull(probabilities);
        if (releaseId == null || releaseId.isBlank() || releaseEpoch < 0 || modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("L2 result requires release and model identities");
        }
    }
    public long candidateId() { return l1.candidateId(); }
}
