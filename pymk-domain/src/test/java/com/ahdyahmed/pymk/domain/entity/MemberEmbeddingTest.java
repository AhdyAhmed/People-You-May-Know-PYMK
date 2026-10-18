package com.ahdyahmed.pymk.domain.entity;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MemberEmbeddingTest {
    private final Instant now = Instant.parse("2026-01-03T00:00:00Z");

    @Test
    void validatesAndDefensivelyCopiesVectorsAndProvenance() {
        float[] vector = new float[128];
        vector[0] = 1;
        var embedding = new MemberEmbedding(1L, vector, now, "profile-svd-v1", now);
        vector[0] = 9;
        embedding.getEmbedding()[0] = 8;
        assertThat(embedding.getEmbedding()[0]).isEqualTo(1);
        assertThat(embedding.getEmbeddingVersion()).isEqualTo("profile-svd-v1");
        assertThat(embedding.getFitCutoff()).isEqualTo(now);
        assertThat(new MemberEmbedding(1L, new float[128], now).getEmbeddingVersion())
                .isEqualTo("placeholder-v1");
    }

    @Test
    void rejectsInvalidVectorsAndFutureOrMissingFitCutoff() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MemberEmbedding(1L, null, now));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemberEmbedding(1L, new float[127], now));
        float[] vector = new float[128];
        vector[0] = Float.NaN;
        assertThatIllegalArgumentException().isThrownBy(() -> new MemberEmbedding(1L, vector, now));
        vector[0] = Float.POSITIVE_INFINITY;
        assertThatIllegalArgumentException().isThrownBy(() -> new MemberEmbedding(1L, vector, now));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MemberEmbedding(1L, new float[128], now, "profile-v1", null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MemberEmbedding(1L, new float[128], now, "profile-v1", now.plusSeconds(1)));
    }
}
