package com.ahdyahmed.pymk.lightranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class L1LightRankerTest {

    private static final long MEMBER = 100L;
    private static final Instant SNAPSHOT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant COMPUTED = Instant.parse("2026-10-01T00:01:00Z");

    @Test
    void batchFetchesFeaturesUsesTrainingOrderAndReturnsDeterministicBoundedRanking() {
        PymkPairFeatureRepository repository = mock(PymkPairFeatureRepository.class);
        CapturingModel model = new CapturingModel(new float[] {0.8f, 0.9f, 0.9f});
        List<MergedCandidate> candidates = List.of(
                candidate(20, 1.0), candidate(30, 3.0), candidate(10, 2.0));
        when(repository.findForCandidates(MEMBER, List.of(20L, 30L, 10L)))
                .thenReturn(List.of(
                        feature(10, 6, false, true, false, -0.25, 9, SNAPSHOT),
                        feature(20, 4, true, false, true, 0.75, 7, SNAPSHOT),
                        feature(30, 5, false, false, true, 0.5, 8, SNAPSHOT)));

        List<LightRankedCandidate> ranked =
                new L1LightRanker(repository, model, 2).rank(MEMBER, candidates);

        assertThat(ranked).extracting(LightRankedCandidate::candidateId)
                .containsExactly(30L, 10L);
        assertThat(ranked).allSatisfy(item -> {
            assertThat(item.featureAsOf()).isEqualTo(SNAPSHOT);
            assertThat(item.modelVersion()).isEqualTo("test-v1");
        });
        assertThat(model.features).isDeepEqualTo(new float[][] {
            {4, 1, 0, 1, 0.75f, 7},
            {5, 0, 0, 1, 0.5f, 8},
            {6, 0, 1, 0, -0.25f, 9}
        });
        verify(repository).findForCandidates(MEMBER, List.of(20L, 30L, 10L));
    }

    @Test
    void failsClosedBeforeInferenceWhenAnyFeatureIsMissing() {
        PymkPairFeatureRepository repository = mock(PymkPairFeatureRepository.class);
        CapturingModel model = new CapturingModel(new float[] {0.5f});
        when(repository.findForCandidates(MEMBER, List.of(10L, 20L)))
                .thenReturn(List.of(feature(10, 1, false, false, false, 0, 0, SNAPSHOT)));

        assertThatThrownBy(() -> new L1LightRanker(repository, model, 500)
                        .rank(MEMBER, List.of(candidate(10, 1), candidate(20, 1))))
                .isInstanceOf(LightRankerException.class)
                .hasMessageContaining("20");
        assertThat(model.features).isNull();
    }

    @Test
    void rejectsMixedSnapshotsDuplicateCandidatesAndSelfCandidates() {
        PymkPairFeatureRepository repository = mock(PymkPairFeatureRepository.class);
        CapturingModel model = new CapturingModel(new float[] {0.5f, 0.4f});
        when(repository.findForCandidates(MEMBER, List.of(10L, 20L)))
                .thenReturn(List.of(
                        feature(10, 1, false, false, false, 0, 0, SNAPSHOT),
                        feature(20, 1, false, false, false, 0, 0, SNAPSHOT.plusSeconds(1))));

        L1LightRanker ranker = new L1LightRanker(repository, model, 500);
        assertThatThrownBy(() -> ranker.rank(
                        MEMBER, List.of(candidate(10, 1), candidate(20, 1))))
                .isInstanceOf(LightRankerException.class)
                .hasMessageContaining("multiple feature_as_of");
        assertThatIllegalArgumentException().isThrownBy(() -> ranker.rank(
                MEMBER, List.of(candidate(10, 1), candidate(10, 2))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ranker.rank(MEMBER, List.of(candidate(MEMBER, 1))));
    }

    @Test
    void emptyCandidateBatchDoesNotTouchStorageOrModel() {
        PymkPairFeatureRepository repository = mock(PymkPairFeatureRepository.class);
        LightRankerModel model = mock(LightRankerModel.class);

        assertThat(new L1LightRanker(repository, model, 500).rank(MEMBER, List.of())).isEmpty();

        verifyNoInteractions(repository, model);
    }

    @Test
    void invalidModelProbabilitiesFailWithTheServingExceptionContract() {
        PymkPairFeatureRepository repository = mock(PymkPairFeatureRepository.class);
        when(repository.findForCandidates(MEMBER, List.of(10L)))
                .thenReturn(List.of(feature(10, 1, false, false, false, 0, 0, SNAPSHOT)));
        for (float score : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f}) {
            assertThatThrownBy(() -> new L1LightRanker(repository,
                    new CapturingModel(new float[] {score}), 500)
                    .rank(MEMBER, List.of(candidate(10, 1))))
                    .isInstanceOf(LightRankerException.class)
                    .hasMessageContaining("invalid positive-class probability");
        }
    }

    private static MergedCandidate candidate(long candidateId, double fusionScore) {
        return new MergedCandidate(
                candidateId,
                fusionScore,
                List.of(new CandidateHit(
                        candidateId, CandidateSourceType.HEURISTIC, fusionScore, Map.of())));
    }

    private static PymkPairFeature feature(
            long candidateId,
            int mutuals,
            boolean company,
            boolean school,
            boolean geo,
            double cosine,
            int views,
            Instant snapshot) {
        return new PymkPairFeature(
                new PairId(MEMBER, candidateId), mutuals, company, school, geo,
                cosine, views, snapshot, COMPUTED.plusSeconds(10));
    }

    private static final class CapturingModel implements LightRankerModel {
        private final float[] scores;
        private float[][] features;

        private CapturingModel(float[] scores) {
            this.scores = scores;
        }

        @Override
        public float[] score(float[][] features) {
            this.features = features;
            return scores;
        }

        @Override
        public String modelVersion() {
            return "test-v1";
        }
    }
}
