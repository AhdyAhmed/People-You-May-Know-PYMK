package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PromotionTemporalTest {
    @Test void rejectsFutureModelSelectionAndMissingHeavyProvenance() {
        var metadata = EmbeddingReleaseManifest.JSON.createObjectNode();
        metadata.putObject("split").put("trainLabelThroughInclusive", "2026-01-10T00:00:00Z")
                .put("validationLabelThroughInclusive", "2026-01-17T00:00:00Z");
        assertThatThrownBy(() -> PromotionApproval.validateModelTime(metadata, Instant.parse("2026-01-16T00:00:00Z"), false))
                .hasMessageContaining("exceeds serving snapshot");
        assertThatThrownBy(() -> PromotionApproval.validateModelTime(metadata, Instant.parse("2026-02-01T00:00:00Z"), true))
                .hasMessageContaining("Missing heavy training provenance");
    }

    @Test void testLabelsMustBeMatureEvenWhenTrainingAndValidationAreMature() {
        var metadata = EmbeddingReleaseManifest.JSON.createObjectNode();
        metadata.putObject("split").put("trainLabelThroughInclusive", "2026-01-10T00:00:00Z")
                .put("validationLabelThroughInclusive", "2026-01-17T00:00:00Z");
        metadata.putArray("inputs").addObject().putObject("provenance").put("labelWindowEnd", "2026-01-24T00:00:00Z");
        assertThatThrownBy(() -> PromotionApproval.validateModelTime(metadata, Instant.parse("2026-01-23T00:00:00Z"), true))
                .hasMessageContaining("Heavy outcomes exceed");
        assertThatCode(() -> PromotionApproval.validateModelTime(metadata, Instant.parse("2026-01-24T00:00:00Z"), true)).doesNotThrowAnyException();
    }
}
