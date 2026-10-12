package com.ahdyahmed.pymk.featurestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PairFeatureCalculatorsTest {

    private static final double TOLERANCE = 1.0e-12;

    @Test
    void profileMatchRequiresTheSameNonBlankCanonicalValue() {
        assertThat(PairFeatureCalculators.sameProfileValue("Acme", "Acme")).isTrue();
        assertThat(PairFeatureCalculators.sameProfileValue("Acme", "Other")).isFalse();
        assertThat(PairFeatureCalculators.sameProfileValue("Acme", "acme")).isFalse();
        assertThat(PairFeatureCalculators.sameProfileValue(null, null)).isFalse();
        assertThat(PairFeatureCalculators.sameProfileValue(" ", " ")).isFalse();
    }

    @Test
    void cosineSimilarityHandlesAlignedOrthogonalAndOppositeVectors() {
        assertThat(PairFeatureCalculators.cosineSimilarity(
                        new float[] {1, 0}, new float[] {1, 0}))
                .isCloseTo(1.0, withinTolerance());
        assertThat(PairFeatureCalculators.cosineSimilarity(
                        new float[] {1, 0}, new float[] {0, 1}))
                .isCloseTo(0.0, withinTolerance());
        assertThat(PairFeatureCalculators.cosineSimilarity(
                        new float[] {1, 0}, new float[] {-1, 0}))
                .isCloseTo(-1.0, withinTolerance());
        assertThat(PairFeatureCalculators.cosineSimilarity(
                        new float[] {3, 4}, new float[] {4, 3}))
                .isCloseTo(0.96, withinTolerance());
    }

    @Test
    void cosineSimilarityUsesNeutralValueForMissingOrZeroVectors() {
        assertThat(PairFeatureCalculators.cosineSimilarity(null, new float[] {1})).isZero();
        assertThat(PairFeatureCalculators.cosineSimilarity(new float[] {1}, null)).isZero();
        assertThat(PairFeatureCalculators.cosineSimilarity(
                        new float[] {0, 0}, new float[] {1, 2}))
                .isZero();
    }

    @Test
    void cosineSimilarityRejectsMalformedVectors() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                PairFeatureCalculators.cosineSimilarity(new float[] {1}, new float[] {1, 2}));
        assertThatIllegalArgumentException().isThrownBy(() ->
                PairFeatureCalculators.cosineSimilarity(new float[0], new float[0]));
        assertThatIllegalArgumentException().isThrownBy(() ->
                PairFeatureCalculators.cosineSimilarity(
                        new float[] {Float.NaN}, new float[] {1}));
    }

    private static org.assertj.core.data.Offset<Double> withinTolerance() {
        return org.assertj.core.data.Offset.offset(TOLERANCE);
    }
}
