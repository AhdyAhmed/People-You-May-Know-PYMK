package com.ahdyahmed.pymk.heavyranker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ahdyahmed.pymk.candidategen.*;
import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.ahdyahmed.pymk.featurestore.*;
import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class L2HeavyRankerTest {
    final ServingReleaseStore releases = mock(ServingReleaseStore.class);
    final ServingReleaseStore.Scope scope = mock(ServingReleaseStore.Scope.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PymkPairFeatureRepository features = mock(PymkPairFeatureRepository.class);
    final HeavyRankerModel model = mock(HeavyRankerModel.class);
    final Instant snapshot = Instant.parse("2026-02-01T00:00:00Z");
    final Instant fit = Instant.parse("2026-01-01T00:00:00Z");
    final L2HeavyRanker ranker = new L2HeavyRanker(releases, jdbc, features, model, 500);

    @BeforeEach void setup() {
        when(releases.pin()).thenReturn(scope);
        when(releases.current()).thenReturn(new ServingReleaseStore.ActiveRelease("live", 3, "l1-v1"));
        when(jdbc.queryForMap(anyString(), eq("live"))).thenReturn(Map.of("feature_as_of", Timestamp.from(snapshot), "embedding_version", "profile-v1", "transform_sha256", "a".repeat(64), "fit_cutoff", Timestamp.from(fit)));
        when(model.compatibility()).thenReturn(new HeavyRankerModel.Compatibility("pair-v1", "profile-v1", "a".repeat(64), fit, fit));
        when(model.modelVersion()).thenReturn("heavy-v1");
        when(features.findForCandidates(eq(1L), anyList())).thenReturn(List.of(row(3, snapshot), row(2, snapshot)));
        when(model.score(any())).thenReturn(List.of(new HeavyRankerModel.Probabilities(.2f, .1f), new HeavyRankerModel.Probabilities(.9f, .8f)));
    }
    LightRankedCandidate candidate(long id) {
        return new LightRankedCandidate(new MergedCandidate(id, .1, List.of(new CandidateHit(id, CandidateSourceType.HEURISTIC, 1, Map.of()))), .5, (int) id, snapshot, "l1-v1");
    }
    PymkPairFeature row(long id, Instant time) {
        return new PymkPairFeature(new PairId(1L, id), (int) id, true, false, true, .25, 4, time, snapshot);
    }
    List<LightRankedCandidate> survivors() { return List.of(candidate(2), candidate(3)); }

    @Test void scoresEverySurvivorOnceWithAlignedFeaturesAndPreservesOrder() {
        var result = ranker.score(1, survivors());
        assertThat(result).extracting(HeavyScoredCandidate::candidateId).containsExactly(2L, 3L);
        assertThat(result.getFirst().releaseEpoch()).isEqualTo(3);
        assertThat(result.getFirst().modelVersion()).isEqualTo("heavy-v1");
        assertThat(result.getFirst().probabilities().inviteAccepted()).isEqualTo(.1f);
        verify(model).score(argThat(matrix -> Arrays.equals(matrix[0], new float[]{2,1,0,1,.25f,4}) && Arrays.equals(matrix[1], new float[]{3,1,0,1,.25f,4})));
        verify(features).findForCandidates(1L, List.of(2L,3L));
        verify(scope).close();
    }
    @Test void emptyBatchDoesNotReadOrInfer() {
        assertThat(ranker.score(1, List.of())).isEmpty();
        verifyNoInteractions(releases, jdbc, features, model);
    }
    @Test void rejectsSelfDuplicatesAndOversizedBatchWithoutTruncation() {
        assertThatThrownBy(() -> ranker.score(1, List.of(candidate(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranker.score(1, List.of(candidate(2),candidate(2)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new L2HeavyRanker(releases,jdbc,features,model,1).score(1,survivors())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(releases);
    }
    @Test void rejectsMissingAndMixedSnapshotFeaturesAndClosesPin() {
        when(features.findForCandidates(eq(1L),anyList())).thenReturn(List.of(row(2,snapshot)));
        assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("Missing");
        when(features.findForCandidates(eq(1L),anyList())).thenReturn(List.of(row(2,fit),row(3,snapshot)));
        assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("mixed-snapshot");
        verify(model,never()).score(any()); verify(scope,times(2)).close();
    }
    @Test void rejectsDuplicateAndForeignFeatureRows() {
        for (var rows : List.of(List.of(row(2,snapshot),row(2,snapshot)),List.of(row(2,snapshot),row(4,snapshot)))) {
            when(features.findForCandidates(eq(1L),anyList())).thenReturn(rows);
            assertThatThrownBy(() -> ranker.score(1,survivors())).isInstanceOf(HeavyRankerException.class);
        }
        verify(model,never()).score(any());
    }
    @Test void rejectsLegacyOrStaleL1Survivors() {
        when(releases.current()).thenReturn(new ServingReleaseStore.ActiveRelease(null,0,"l1-v1"));
        assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("managed");
        when(releases.current()).thenReturn(new ServingReleaseStore.ActiveRelease("live",4,"new-l1"));
        assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("L1 survivors");
    }
    @Test void rejectsIncompatibleEmbeddingOrFutureModelSelection() {
        for (var contract : List.of(
                new HeavyRankerModel.Compatibility("pair-v2","profile-v1","a".repeat(64),fit,fit),
                new HeavyRankerModel.Compatibility("pair-v1","other","a".repeat(64),fit,fit),
                new HeavyRankerModel.Compatibility("pair-v1","profile-v1","b".repeat(64),fit,fit),
                new HeavyRankerModel.Compatibility("pair-v1","profile-v1","a".repeat(64),snapshot,fit),
                new HeavyRankerModel.Compatibility("pair-v1","profile-v1","a".repeat(64),fit,snapshot.plusSeconds(1)))) {
            when(model.compatibility()).thenReturn(contract);
            assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("incompatible");
        }
        verify(model,never()).score(any());
    }
    @Test void rejectsIncompleteOrNullModelResults() {
        when(model.score(any())).thenReturn(List.of());
        assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("incomplete");
        when(model.score(any())).thenReturn(Arrays.asList(null,null));
        assertThatThrownBy(() -> ranker.score(1,survivors())).hasMessageContaining("null score");
    }
}
