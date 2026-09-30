package com.ahdyahmed.pymk.datagen.generate;

import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import java.util.Random;

/**
 * Random, L2-normalized placeholder embeddings (Day 5, PYMK_ROADMAP.md:
 * "Generate placeholder embeddings for synthetic members - random vectors
 * is fine for now"). They carry no semantic meaning by construction; they
 * exist so the L0 embedding-retrieval path (Day 9) and the ANN query
 * mechanism have real rows to run against end to end. Real embeddings
 * (node2vec over the connection graph, or profile-text embeddings) replace
 * these in Week 4 via EmbeddingRefreshJob.
 *
 * <p>Normalized to unit length because that's what cosine distance assumes;
 * an un-normalized random vector would still "work" but distances wouldn't
 * mean much even as a placeholder.</p>
 */
public final class PlaceholderEmbeddingFactory {

    private final Random random;

    public PlaceholderEmbeddingFactory(Random random) {
        this.random = random;
    }

    public float[] create() {
        float[] vector = new float[MemberEmbedding.DIMENSIONS];
        double sumSquares = 0;
        for (int i = 0; i < vector.length; i++) {
            double gaussian = random.nextGaussian();
            vector[i] = (float) gaussian;
            sumSquares += gaussian * gaussian;
        }
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
