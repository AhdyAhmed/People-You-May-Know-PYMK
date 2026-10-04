package com.ahdyahmed.pymk.candidategen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateHitTest {

    @Test
    void metadataIsDefensivelyCopiedAndImmutable() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("company", "Acme");

        CandidateHit hit = new CandidateHit(2L, CandidateSourceType.HEURISTIC, 0.5, metadata);
        metadata.put("school", "MIT");

        assertThat(hit.metadata()).containsExactly(Map.entry("company", "Acme"));
        assertThatThrownBy(() -> hit.metadata().put("school", "MIT"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidIdentityAndScore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CandidateHit(0, CandidateSourceType.HEURISTIC, 1.0, Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CandidateHit(1, CandidateSourceType.HEURISTIC, Double.NaN, Map.of()));
    }
}
