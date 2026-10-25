package com.ahdyahmed.pymk.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;

import com.ahdyahmed.pymk.candidategen.CandidateGenerationException;
import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.L0CandidateGenerator;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.lightranker.L1LightRanker;
import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import com.ahdyahmed.pymk.lightranker.LightRankerException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationOrchestratorTest {
    @Test void rerankerRejectsMissingMemberRatherThanTreatingItAsUnknownEmployer() {
        var heavy = mock(com.ahdyahmed.pymk.heavyranker.L2HeavyRanker.class);
        var model = mock(com.ahdyahmed.pymk.heavyranker.HeavyRankerModel.class);
        when(model.artifactIdentity()).thenReturn("a".repeat(64));
        org.springframework.test.util.ReflectionTestUtils.setField(orchestrator,"pipeline",new ServingPipeline("RERANK",heavy,model,500,500));
        when(members.existsById(42L)).thenReturn(true);
        var candidates=List.of(candidate(101,.3,CandidateSourceType.EMBEDDING,Map.of()));
        var survivors=candidates.stream().map(c -> ranked(c,.5,0)).toList();
        when(generator.generate(42L)).thenReturn(candidates);
        when(lightRanker.rank(42L,candidates)).thenReturn(survivors);
        when(heavy.score(42L,survivors)).thenReturn(List.of(new com.ahdyahmed.pymk.heavyranker.HeavyScoredCandidate(
                survivors.getFirst(),new com.ahdyahmed.pymk.heavyranker.HeavyRankerModel.Probabilities(.6f,.7f),"release",1,"heavy")));
        when(members.findAllById(List.of(101L))).thenReturn(List.of());
        assertThatThrownBy(() -> orchestrator.getRecommendations(42,20)).isInstanceOf(RecommendationGenerationException.class)
                .hasCauseInstanceOf(com.ahdyahmed.pymk.heavyranker.HeavyRankerException.class);
        verify(cache,never()).putPool(anyLong(),org.mockito.ArgumentMatchers.any());
    }
    @Test
    void l2ScoresAllSurvivorsBeforeLimitingAndUsesAcceptedHeadWithIdTies() {
        var heavy = mock(com.ahdyahmed.pymk.heavyranker.L2HeavyRanker.class);
        var model = mock(com.ahdyahmed.pymk.heavyranker.HeavyRankerModel.class);
        when(model.artifactIdentity()).thenReturn("a".repeat(64));
        var pipeline = new ServingPipeline("L2",heavy,model,500,500);
        org.springframework.test.util.ReflectionTestUtils.setField(orchestrator,"pipeline",pipeline);
        when(members.existsById(42L)).thenReturn(true);
        var candidates = List.of(candidate(103,.3,CandidateSourceType.EMBEDDING,Map.of()),candidate(101,.2,CandidateSourceType.EMBEDDING,Map.of()),candidate(102,.1,CandidateSourceType.EMBEDDING,Map.of()));
        var survivors = candidates.stream().map(c -> ranked(c,.5,0)).toList();
        when(generator.generate(42L)).thenReturn(candidates);
        when(lightRanker.rank(42L,candidates)).thenReturn(survivors);
        when(heavy.score(42L,survivors)).thenReturn(survivors.stream().map(row ->
                new com.ahdyahmed.pymk.heavyranker.HeavyScoredCandidate(row,
                        new com.ahdyahmed.pymk.heavyranker.HeavyRankerModel.Probabilities(row.candidateId()==103 ? .99f : .01f,.7f),"release",1,"heavy")).toList());
        var result = orchestrator.getRecommendations(42,1).orElseThrow();
        assertThat(result.recommendations()).extracting(Recommendation::candidateId).containsExactly(101L);
        verify(heavy).score(42L,survivors);
        verify(cache).putResult(org.mockito.ArgumentMatchers.eq(42L),org.mockito.ArgumentMatchers.argThat(r -> r.recommendations().size()==3));
        doThrow(new com.ahdyahmed.pymk.heavyranker.HeavyRankerException("bad output")).when(heavy).score(42L,survivors);
        assertThatThrownBy(() -> orchestrator.getRecommendations(42,1)).isInstanceOf(RecommendationGenerationException.class);
        verify(cache,org.mockito.Mockito.times(1)).putResult(anyLong(),org.mockito.ArgumentMatchers.any());
    }

    private L0CandidateGenerator generator;
    private L1LightRanker lightRanker;
    private MemberRepository members;
    private RecommendationCache cache;
    private RecommendationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        generator = mock(L0CandidateGenerator.class);
        lightRanker = mock(L1LightRanker.class);
        when(lightRanker.modelVersion()).thenReturn("light-logreg-v1");
        members = mock(MemberRepository.class);
        cache = mock(RecommendationCache.class);
        orchestrator = new RecommendationOrchestrator(generator, lightRanker, members, cache);
    }

    @Test
    void exposesL1ProbabilityOrderKeepsExplanationsAndAppliesLimit() {
        when(members.existsById(42L)).thenReturn(true);
        List<MergedCandidate> candidates = List.of(
                candidate(101, 0.10, CandidateSourceType.GRAPH_WALK, Map.of()),
                candidate(102, 0.40, CandidateSourceType.HEURISTIC, Map.of("company", "Acme")),
                candidate(103, 0.20, CandidateSourceType.EMBEDDING, Map.of()));
        when(generator.generate(42L)).thenReturn(candidates);
        when(lightRanker.rank(42L, candidates)).thenReturn(List.of(
                ranked(candidates.get(2), 0.91, 0),
                ranked(candidates.get(1), 0.73, 2),
                ranked(candidates.get(0), 0.42, 2)));

        RecommendationResult result = orchestrator.getRecommendations(42, 2).orElseThrow();

        assertThat(result.memberId()).isEqualTo(42);
        assertThat(result.recommendations()).extracting(Recommendation::candidateId)
                .containsExactly(103L, 102L);
        assertThat(result.recommendations().getFirst().score()).isEqualTo(0.91);
        assertThat(result.recommendations().get(1).reasons())
                .containsExactly("2 mutual connections", "Same company: Acme");
        assertThatThrownBy(() -> result.recommendations().add(
                new Recommendation(999, 0, 0, List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsEmptyOptionalForMissingMemberWithoutRunningPipeline() {
        when(members.existsById(404L)).thenReturn(false);
        assertThat(orchestrator.getRecommendations(404, 20)).isEmpty();
        verify(generator, never()).generate(404L);
    }

    @Test
    void finalCacheHitIsSlicedAndBypassesCandidateAndFeatureQueries() {
        when(members.existsById(42L)).thenReturn(true);
        RecommendationResult cached = new RecommendationResult(42, List.of(
                new Recommendation(101, 0.8, 2, List.of("2 mutual connections")),
                new Recommendation(102, 0.6, 1, List.of("1 mutual connection"))));
        when(cache.getResult(42L)).thenReturn(Optional.of(cached));

        RecommendationResult result = orchestrator.getRecommendations(42, 1).orElseThrow();

        assertThat(result.recommendations()).extracting(Recommendation::candidateId)
                .containsExactly(101L);
        verifyNoInteractions(generator);
        verify(lightRanker, never()).rank(anyLong(), anyList());
        verify(cache).verifyModelVersion("light-logreg-v1");
    }

    @Test
    void l0CacheHitBypassesCandidateGenerationAndRebuildsFinalResult() {
        when(members.existsById(42L)).thenReturn(true);
        List<MergedCandidate> cachedCandidates = List.of(
                candidate(101, 0.25, CandidateSourceType.GRAPH_WALK, Map.of()));
        when(cache.getCandidates(42L)).thenReturn(Optional.of(cachedCandidates));
        when(lightRanker.rank(42L, cachedCandidates))
                .thenReturn(List.of(ranked(cachedCandidates.getFirst(), 0.81, 2)));

        RecommendationResult result = orchestrator.getRecommendations(42, 20).orElseThrow();

        assertThat(result.recommendations()).extracting(Recommendation::candidateId)
                .containsExactly(101L);
        verify(generator, never()).generate(42L);
        verify(cache).putResult(42L, result);
    }

    @Test
    void l1ChangesTheNaiveMutualCountOrderForRepresentativeCandidates() {
        when(members.existsById(42L)).thenReturn(true);
        List<MergedCandidate> candidates = List.of(
                candidate(101, 0.3, CandidateSourceType.GRAPH_WALK, Map.of()),
                candidate(102, 0.2, CandidateSourceType.HEURISTIC, Map.of("company", "Acme")),
                candidate(103, 0.1, CandidateSourceType.EMBEDDING, Map.of()));
        when(generator.generate(42L)).thenReturn(candidates);
        when(lightRanker.rank(42L, candidates)).thenReturn(List.of(
                ranked(candidates.get(1), 0.90, 0),
                ranked(candidates.get(2), 0.70, 1),
                ranked(candidates.get(0), 0.20, 3)));

        List<Long> naiveOrder = List.of(101L, 103L, 102L);
        List<Long> l1Order = orchestrator.getRecommendations(42, 20).orElseThrow()
                .recommendations().stream().map(Recommendation::candidateId).toList();

        assertThat(l1Order).containsExactly(102L, 103L, 101L).isNotEqualTo(naiveOrder);
    }

    @Test
    void returnsEmptyListForKnownMemberWithNoCandidates() {
        when(members.existsById(42L)).thenReturn(true);
        when(generator.generate(42L)).thenReturn(List.of());
        assertThat(orchestrator.getRecommendations(42, 20).orElseThrow().recommendations()).isEmpty();
        verify(lightRanker, never()).rank(anyLong(), anyList());
    }

    @Test
    void modelNamespaceMismatchFailsBeforeAnyCachedResultCanBeServed() {
        when(members.existsById(42L)).thenReturn(true);
        doThrow(new LightRankerException("model version mismatch"))
                .when(cache).verifyModelVersion("light-logreg-v1");

        assertThatThrownBy(() -> orchestrator.getRecommendations(42, 20))
                .isInstanceOf(RecommendationGenerationException.class)
                .hasCauseInstanceOf(LightRankerException.class);
        verify(cache, never()).getResult(42L);
        verifyNoInteractions(generator);
    }

    @Test
    void exposesCandidateFailureAsRecommendationFailure() {
        when(members.existsById(42L)).thenReturn(true);
        when(generator.generate(42L)).thenThrow(
                new CandidateGenerationException("graph source failed", new IllegalStateException("database")));

        assertThatThrownBy(() -> orchestrator.getRecommendations(42, 20))
                .isInstanceOf(RecommendationGenerationException.class)
                .hasMessageContaining("member 42")
                .hasCauseInstanceOf(CandidateGenerationException.class);
    }

    @Test
    void exposesL1FailureAsRecommendationFailureInsteadOfPartialResults() {
        when(members.existsById(42L)).thenReturn(true);
        List<MergedCandidate> candidates = List.of(
                candidate(101, 0.5, CandidateSourceType.GRAPH_WALK, Map.of()));
        when(generator.generate(42L)).thenReturn(candidates);
        when(lightRanker.rank(42L, candidates))
                .thenThrow(new LightRankerException("missing feature row"));

        assertThatThrownBy(() -> orchestrator.getRecommendations(42, 20))
                .isInstanceOf(RecommendationGenerationException.class)
                .hasMessageContaining("rank complete")
                .hasCauseInstanceOf(LightRankerException.class);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> orchestrator.getRecommendations(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.getRecommendations(42, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MergedCandidate candidate(
            long id, double fusionScore, CandidateSourceType source, Map<String, String> metadata) {
        return new MergedCandidate(id, fusionScore,
                List.of(new CandidateHit(id, source, 0.5, metadata)));
    }

    private static LightRankedCandidate ranked(
            MergedCandidate candidate, double score, int mutualCount) {
        return new LightRankedCandidate(
                candidate,
                score,
                mutualCount,
                Instant.parse("2026-10-01T00:00:00Z"),
                "light-logreg-test");
    }
}
