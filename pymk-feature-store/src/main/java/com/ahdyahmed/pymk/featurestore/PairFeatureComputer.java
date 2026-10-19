package com.ahdyahmed.pymk.featurestore;

import com.ahdyahmed.pymk.domain.entity.EventType;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberEmbeddingRepository;
import com.ahdyahmed.pymk.domain.repository.MemberEventRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes the complete Day 16 feature vector for one directional pair. */
@Service
public class PairFeatureComputer {

    public static final Duration PROFILE_VIEW_WINDOW = Duration.ofDays(30);

    private final MemberRepository members;
    private final ConnectionRepository connections;
    private final MemberEventRepository events;
    private final MemberEmbeddingRepository embeddings;

    public PairFeatureComputer(
            MemberRepository members,
            ConnectionRepository connections,
            MemberEventRepository events,
            MemberEmbeddingRepository embeddings) {
        this.members = members;
        this.connections = connections;
        this.events = events;
        this.embeddings = embeddings;
    }

    /**
     * Computes but does not persist a row; Day 17 owns chunked upserts. Every
     * time-varying source is bounded by {@code featureAsOf}.
     */
    @Transactional(readOnly = true)
    public PymkPairFeature compute(PairId pairId, Instant featureAsOf, Instant computedAt) {
        Objects.requireNonNull(pairId, "pairId must not be null");
        var memberVector = embeddings.findByMemberIdAndUpdatedAtLessThanEqual(pairId.getMemberId(), featureAsOf);
        var candidateVector = embeddings.findByMemberIdAndUpdatedAtLessThanEqual(pairId.getCandidateId(), featureAsOf);
        return compute(pairId, featureAsOf, computedAt, memberVector.orElse(null), candidateVector.orElse(null));
    }

    /** Compute against an explicit staged embedding generation without reading live vectors. */
    @Transactional(readOnly = true)
    public PymkPairFeature compute(PairId pairId, Instant featureAsOf, Instant computedAt,
            com.ahdyahmed.pymk.domain.entity.MemberEmbedding requesterEmbedding,
            com.ahdyahmed.pymk.domain.entity.MemberEmbedding targetEmbedding) {
        Objects.requireNonNull(pairId, "pairId must not be null");
        Objects.requireNonNull(featureAsOf, "featureAsOf must not be null");
        Objects.requireNonNull(computedAt, "computedAt must not be null");
        if (featureAsOf.isAfter(computedAt)) {
            throw new IllegalArgumentException("featureAsOf must not be after computedAt");
        }

        Member member = memberAtSnapshot(pairId.getMemberId(), featureAsOf, "member");
        Member candidate = memberAtSnapshot(pairId.getCandidateId(), featureAsOf, "candidate");

        int mutualConnectionCount = Math.toIntExact(connections.countMutualConnectionsAsOf(
                pairId.getMemberId(), pairId.getCandidateId(), featureAsOf));
        int profileViewCount = Math.toIntExact(events.countPairEventsInWindow(
                pairId.getMemberId(),
                pairId.getCandidateId(),
                EventType.PROFILE_VIEW,
                featureAsOf.minus(PROFILE_VIEW_WINDOW),
                featureAsOf));

        var memberVector = java.util.Optional.ofNullable(requesterEmbedding).filter(e -> !e.getUpdatedAt().isAfter(featureAsOf));
        var candidateVector = java.util.Optional.ofNullable(targetEmbedding).filter(e -> !e.getUpdatedAt().isAfter(featureAsOf));
        if (memberVector.isPresent() && !memberVector.get().getMemberId().equals(pairId.getMemberId())
                || candidateVector.isPresent() && !candidateVector.get().getMemberId().equals(pairId.getCandidateId())) {
            throw new IllegalArgumentException("Staged embedding belongs to a different member");
        }
        if (memberVector.isPresent() && candidateVector.isPresent()
                && (!memberVector.get().getEmbeddingVersion().equals(candidateVector.get().getEmbeddingVersion())
                    || !Objects.equals(memberVector.get().getFitCutoff(), candidateVector.get().getFitCutoff()))) {
            throw new FeatureInputUnavailableException("Cannot compare different embedding transforms");
        }
        float[] memberEmbedding = memberVector.map(e -> e.getEmbedding()).orElse(null);
        float[] candidateEmbedding = candidateVector.map(e -> e.getEmbedding()).orElse(null);

        return new PymkPairFeature(
                pairId,
                mutualConnectionCount,
                PairFeatureCalculators.sameProfileValue(member.getCompany(), candidate.getCompany()),
                PairFeatureCalculators.sameProfileValue(member.getSchool(), candidate.getSchool()),
                PairFeatureCalculators.sameProfileValue(member.getGeoRegion(), candidate.getGeoRegion()),
                PairFeatureCalculators.cosineSimilarity(memberEmbedding, candidateEmbedding),
                profileViewCount,
                featureAsOf,
                computedAt);
    }

    private Member memberAtSnapshot(long memberId, Instant featureAsOf, String role) {
        Member member = members.findById(memberId).orElseThrow(() ->
                new FeatureInputUnavailableException(role + " " + memberId + " does not exist"));
        if (member.getCreatedAt().isAfter(featureAsOf)) {
            throw new FeatureInputUnavailableException(
                    role + " " + memberId + " did not exist at snapshot " + featureAsOf);
        }
        return member;
    }

}
