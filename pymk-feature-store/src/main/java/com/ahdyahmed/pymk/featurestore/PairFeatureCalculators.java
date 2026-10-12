package com.ahdyahmed.pymk.featurestore;

/** Pure feature functions shared by online computation and the Day 17 batch job. */
public final class PairFeatureCalculators {

    private PairFeatureCalculators() {
    }

    /** Missing and blank profile attributes are unknown, so they never count as a match. */
    public static boolean sameProfileValue(String memberValue, String candidateValue) {
        return memberValue != null
                && candidateValue != null
                && !memberValue.isBlank()
                && !candidateValue.isBlank()
                && memberValue.equals(candidateValue);
    }

    /**
     * Computes cosine similarity without mutating either vector. A zero vector
     * has no direction and therefore contributes the neutral feature value 0.
     */
    public static double cosineSimilarity(float[] memberVector, float[] candidateVector) {
        if (memberVector == null || candidateVector == null) {
            return 0.0;
        }
        if (memberVector.length != candidateVector.length) {
            throw new IllegalArgumentException("embedding vectors must have equal dimensions");
        }
        if (memberVector.length == 0) {
            throw new IllegalArgumentException("embedding vectors must not be empty");
        }

        double dotProduct = 0.0;
        double memberNormSquared = 0.0;
        double candidateNormSquared = 0.0;
        for (int index = 0; index < memberVector.length; index++) {
            double memberValue = memberVector[index];
            double candidateValue = candidateVector[index];
            if (!Double.isFinite(memberValue) || !Double.isFinite(candidateValue)) {
                throw new IllegalArgumentException("embedding vectors must contain only finite values");
            }
            dotProduct += memberValue * candidateValue;
            memberNormSquared += memberValue * memberValue;
            candidateNormSquared += candidateValue * candidateValue;
        }

        if (memberNormSquared == 0.0 || candidateNormSquared == 0.0) {
            return 0.0;
        }

        double similarity = dotProduct / Math.sqrt(memberNormSquared * candidateNormSquared);
        // Floating-point rounding can produce values a few ulps outside the legal feature range.
        return Math.max(-1.0, Math.min(1.0, similarity));
    }
}
