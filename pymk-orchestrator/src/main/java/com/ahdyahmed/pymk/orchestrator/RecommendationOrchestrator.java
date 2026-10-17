package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.candidategen.CandidateGenerationException;
import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.L0CandidateGenerator;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.lightranker.L1LightRanker;
import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import com.ahdyahmed.pymk.lightranker.LightRankerException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Online pipeline composition: cached L0 generation followed by the Day 20 L1 ranker. */
@Service
public class RecommendationOrchestrator {

    public static final int MAX_RESULTS = 100;

    private final L0CandidateGenerator candidateGenerator;
    private final L1LightRanker lightRanker;
    private final MemberRepository members;
    private final RecommendationCache cache;

    public RecommendationOrchestrator(
            L0CandidateGenerator candidateGenerator,
            L1LightRanker lightRanker,
            MemberRepository members,
            RecommendationCache cache) {
        this.candidateGenerator = candidateGenerator;
        this.lightRanker = lightRanker;
        this.members = members;
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

        try {
            cache.verifyModelVersion(lightRanker.modelVersion());
        } catch (LightRankerException ex) {
            throw new RecommendationGenerationException("L1 model and cache configuration disagree", ex);
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

        final List<LightRankedCandidate> ranked;
        try {
            ranked = lightRanker.rank(memberId, candidates);
        } catch (LightRankerException | DataAccessException ex) {
            throw new RecommendationGenerationException(
                    "Unable to rank complete recommendations for member " + memberId, ex);
        }

        List<Recommendation> recommendations = ranked.stream()
                .limit(MAX_RESULTS)
                .map(RecommendationOrchestrator::toRecommendation)
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

    private static Recommendation toRecommendation(LightRankedCandidate ranked) {
        MergedCandidate candidate = ranked.candidate();
        long mutualCount = ranked.mutualConnectionCount();
        return new Recommendation(
                candidate.candidateId(),
                ranked.score(),
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
