package com.ahdyahmed.pymk.candidategen;

import java.util.List;

/** Contract implemented by each L0 retrieval strategy. */
public interface CandidateSource {

    CandidateSourceType type();

    /**
     * Returns at most {@code limit} eligible candidates in deterministic
     * source preference order.
     */
    List<CandidateHit> generate(long memberId, int limit);

    static void validateRequest(long memberId, int limit) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
