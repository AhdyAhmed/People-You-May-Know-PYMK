package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.candidategen.CandidateGenerationException;
import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.L0CandidateGenerator;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Day 11 baseline orchestrator: L0 followed by mutual-connection ordering. */
@Service
public class RecommendationOrchestrator {

    private final L0CandidateGenerator candidateGenerator;
    private final MemberRepository members;
    private final ConnectionRepository connections;

    public RecommendationOrchestrator(
            L0CandidateGenerator candidateGenerator,
            MemberRepository members,
            ConnectionRepository connections) {
        this.candidateGenerator = candidateGenerator;
        this.members = members;
        this.connections = connections;
    }

    /**
     * Returns an empty optional only when the requesting member does not exist.
     * A pipeline failure never masquerades as an empty recommendation list.
     */
    public Optional<RecommendationResult> getRecommendations(long memberId, int limit) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (!members.existsById(memberId)) {
            return Optional.empty();
        }

        final List<MergedCandidate> candidates;
        try {
            candidates = candidateGenerator.generate(memberId);
        } catch (CandidateGenerationException ex) {
            throw new RecommendationGenerationException(
                    "Unable to generate complete recommendations for member " + memberId, ex);
        }

        if (candidates.isEmpty()) {
            return Optional.of(new RecommendationResult(memberId, List.of()));
        }

        List<Long> candidateIds = candidates.stream().map(MergedCandidate::candidateId).toList();
        Map<Long, Long> mutualCounts = connections
                .countMutualConnectionsForCandidates(memberId, candidateIds)
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        ConnectionRepository.MutualConnectionCount::getCandidateId,
                        ConnectionRepository.MutualConnectionCount::getMutualConnectionCount));

        List<Recommendation> recommendations = candidates.stream()
                .sorted(Comparator
                        .comparingLong((MergedCandidate candidate) ->
                                mutualCounts.getOrDefault(candidate.candidateId(), 0L))
                        .reversed()
                        .thenComparing(MergedCandidate::fusionScore, Comparator.reverseOrder())
                        .thenComparingLong(MergedCandidate::candidateId))
                .limit(limit)
                .map(candidate -> toRecommendation(
                        candidate, mutualCounts.getOrDefault(candidate.candidateId(), 0L)))
                .toList();
        return Optional.of(new RecommendationResult(memberId, recommendations));
    }

    private static Recommendation toRecommendation(MergedCandidate candidate, long mutualCount) {
        return new Recommendation(
                candidate.candidateId(),
                mutualCount,
                mutualCount,
                reasons(candidate.sourceHits(), mutualCount));
    }

    private static List<String> reasons(List<CandidateHit> hits, long mutualCount) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (mutualCount > 0) {
            reasons.add(mutualCount + (mutualCount == 1
                    ? " mutual connection"
                    : " mutual connections"));
        }
        for (CandidateHit hit : hits) {
            if (hit.source() == CandidateSourceType.HEURISTIC) {
                addMetadataReason(reasons, hit, "company", "Same company: ");
                addMetadataReason(reasons, hit, "school", "Same school: ");
                addMetadataReason(reasons, hit, "geoRegion", "Same region: ");
            } else if (hit.source() == CandidateSourceType.GRAPH_WALK && mutualCount == 0) {
                reasons.add("Nearby in your connection graph");
            } else if (hit.source() == CandidateSourceType.EMBEDDING) {
                reasons.add("Similar network profile");
            }
        }
        return List.copyOf(reasons);
    }

    private static void addMetadataReason(
            LinkedHashSet<String> reasons, CandidateHit hit, String key, String prefix) {
        String value = hit.metadata().get(key);
        if (value != null && !value.isBlank()) {
            reasons.add(prefix + value);
        }
    }
}
