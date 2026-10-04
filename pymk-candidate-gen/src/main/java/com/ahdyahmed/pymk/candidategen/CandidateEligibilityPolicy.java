package com.ahdyahmed.pymk.candidategen;

import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shared L0 safety net for candidate eligibility and stable de-duplication. */
@Component
public class CandidateEligibilityPolicy {

    private final MemberRepository members;
    private final ConnectionRepository connections;

    public CandidateEligibilityPolicy(MemberRepository members, ConnectionRepository connections) {
        this.members = members;
        this.connections = connections;
    }

    /**
     * Keeps the first hit for each candidate ID, preserving source order.
     * Apply this to one source at a time; the Day 10 merger will combine
     * provenance from different sources before global de-duplication.
     */
    public List<CandidateHit> filter(long memberId, Collection<CandidateHit> hits) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        Objects.requireNonNull(hits, "hits must not be null");
        if (hits.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<Long, CandidateHit> unique = new LinkedHashMap<>();
        for (CandidateHit hit : hits) {
            Objects.requireNonNull(hit, "hits must not contain null");
            if (hit.candidateId() != memberId) {
                unique.putIfAbsent(hit.candidateId(), hit);
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }

        Set<Long> existingIds = new HashSet<>();
        members.findAllById(unique.keySet()).stream()
                .map(Member::getId)
                .forEach(existingIds::add);
        Set<Long> connectedIds = new HashSet<>(connections.findConnectedMemberIds(memberId));

        return unique.values().stream()
                .filter(hit -> existingIds.contains(hit.candidateId()))
                .filter(hit -> !connectedIds.contains(hit.candidateId()))
                .toList();
    }
}
