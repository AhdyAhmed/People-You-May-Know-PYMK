package com.ahdyahmed.pymk.featurestore;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** Directional composite key for one requester-candidate feature row. */
@Embeddable
public class PairId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    protected PairId() {
        // required by JPA
    }

    public PairId(long memberId, long candidateId) {
        if (memberId <= 0 || candidateId <= 0) {
            throw new IllegalArgumentException("memberId and candidateId must be positive");
        }
        if (memberId == candidateId) {
            throw new IllegalArgumentException("memberId and candidateId must be different");
        }
        this.memberId = memberId;
        this.candidateId = candidateId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getCandidateId() {
        return candidateId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PairId pairId)) return false;
        return Objects.equals(memberId, pairId.memberId)
                && Objects.equals(candidateId, pairId.candidateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memberId, candidateId);
    }

    @Override
    public String toString() {
        return "PairId{memberId=%s, candidateId=%s}".formatted(memberId, candidateId);
    }
}
