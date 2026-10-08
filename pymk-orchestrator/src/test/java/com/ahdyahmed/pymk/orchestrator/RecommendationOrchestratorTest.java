package com.ahdyahmed.pymk.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ahdyahmed.pymk.candidategen.CandidateGenerationException;
import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.L0CandidateGenerator;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationOrchestratorTest {

    private L0CandidateGenerator generator;
    private MemberRepository members;
    private ConnectionRepository connections;
    private RecommendationCache cache;
    private RecommendationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        generator = mock(L0CandidateGenerator.class);
        members = mock(MemberRepository.class);
        connections = mock(ConnectionRepository.class);
        cache = mock(RecommendationCache.class);
        orchestrator = new RecommendationOrchestrator(generator, members, connections, cache);
    }

    @Test
    void ordersByMutualCountThenFusionScoreAndAppliesLimit() {
        when(members.existsById(42L)).thenReturn(true);
        when(generator.generate(42L)).thenReturn(List.of(
                candidate(101, 0.10, CandidateSourceType.GRAPH_WALK, Map.of()),
                candidate(102, 0.40, CandidateSourceType.HEURISTIC, Map.of("company", "Acme")),
                candidate(103, 0.20, CandidateSourceType.EMBEDDING, Map.of())));
        when(connections.countMutualConnectionsForCandidates(42L, List.of(101L, 102L, 103L)))
                .thenReturn(List.of(count(101, 2), count(102, 2)));

        RecommendationResult result = orchestrator.getRecommendations(42, 2).orElseThrow();

        assertThat(result.memberId()).isEqualTo(42);
        assertThat(result.recommendations()).extracting(Recommendation::candidateId)
                .containsExactly(102L, 101L);
        assertThat(result.recommendations().getFirst().score()).isEqualTo(2.0);
        assertThat(result.recommendations().getFirst().reasons())
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
                new Recommendation(101, 2, 2, List.of("2 mutual connections")),
                new Recommendation(102, 1, 1, List.of("1 mutual connection"))));
        when(cache.getResult(42L)).thenReturn(Optional.of(cached));

        RecommendationResult result = orchestrator.getRecommendations(42, 1).orElseThrow();

        assertThat(result.recommendations()).extracting(Recommendation::candidateId)
                .containsExactly(101L);
        verifyNoInteractions(generator, connections);
    }

    @Test
    void l0CacheHitBypassesCandidateGenerationAndRebuildsFinalResult() {
        when(members.existsById(42L)).thenReturn(true);
        List<MergedCandidate> cachedCandidates = List.of(
                candidate(101, 0.25, CandidateSourceType.GRAPH_WALK, Map.of()));
        when(cache.getCandidates(42L)).thenReturn(Optional.of(cachedCandidates));
        when(connections.countMutualConnectionsForCandidates(42L, List.of(101L)))
                .thenReturn(List.of(count(101, 2)));

        RecommendationResult result = orchestrator.getRecommendations(42, 20).orElseThrow();

        assertThat(result.recommendations()).extracting(Recommendation::candidateId)
                .containsExactly(101L);
        verify(generator, never()).generate(42L);
        verify(cache).putResult(42L, result);
    }

    @Test
    void returnsEmptyListForKnownMemberWithNoCandidates() {
        when(members.existsById(42L)).thenReturn(true);
        when(generator.generate(42L)).thenReturn(List.of());
        assertThat(orchestrator.getRecommendations(42, 20).orElseThrow().recommendations()).isEmpty();
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

    private static ConnectionRepository.MutualConnectionCount count(long candidateId, long count) {
        return new ConnectionRepository.MutualConnectionCount() {
            @Override
            public Long getCandidateId() {
                return candidateId;
            }

            @Override
            public long getMutualConnectionCount() {
                return count;
            }
        };
    }
}
