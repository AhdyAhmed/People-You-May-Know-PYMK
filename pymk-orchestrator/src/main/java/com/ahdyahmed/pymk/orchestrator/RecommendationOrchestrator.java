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

    public static final int MAX_RESULTS = 100;

    private final L0CandidateGenerator candidateGenerator;
    private final MemberRepository members;
    private final ConnectionRepository connections;
    private final RecommendationCache cache;

    public RecommendationOrchestrator(
            L0CandidateGenerator candidateGenerator,
            MemberRepository members,
            ConnectionRepository connections,
            RecommendationCache cache) {
        this.candidateGenerator = candidateGenerator;
        this.members = members;
        this.connections = connections;
        this.cache = cache;
    }

    /**
     * Returns an empty optional only when the requesting member does not exist.
     * A pipeline failure never masquerades as an empty recommendation list.
     */
    public Optional<RecommendationResult> getRecommendations(long memberId, int limit) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        if (limit <= 0 || limit > MAX_RESULTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
        }
        if (!members.existsById(memberId)) {
            return Optional.empty();
        }

        Optional<RecommendationResult> cachedResult = cache.getResult(memberId);
        if (cachedResult.isPresent()) {
            return Optional.of(limit(cachedResult.orElseThrow(), limit));
        }

        final List<MergedCandidate> candidates;
        Optional<List<MergedCandidate>> cachedCandidates = cache.getCandidates(memberId);
        if (cachedCandidates.isPresent()) {
            candidates = cachedCandidates.orElseThrow();
        } else {
            try {
                candidates = candidateGenerator.generate(memberId);
                cache.putCandidates(memberId, candidates);
            } catch (CandidateGenerationException ex) {
                throw new RecommendationGenerationException(
                        "Unable to generate complete recommendations for member " + memberId, ex);
            }
        }

        if (candidates.isEmpty()) {
            RecommendationResult empty = new RecommendationResult(memberId, List.of());
            cache.putResult(memberId, empty);
            return Optional.of(empty);
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
                .limit(MAX_RESULTS)
                .map(candidate -> toRecommendation(
                        candidate, mutualCounts.getOrDefault(candidate.candidateId(), 0L)))
                .toList();
        RecommendationResult fullResult = new RecommendationResult(memberId, recommendations);
        cache.putResult(memberId, fullResult);
        return Optional.of(limit(fullResult, limit));
    }

    private static RecommendationResult limit(RecommendationResult result, int limit) {
        if (result.recommendations().size() <= limit) {
            return result;
        }
        return new RecommendationResult(
                result.memberId(), result.recommendations().subList(0, limit));
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
