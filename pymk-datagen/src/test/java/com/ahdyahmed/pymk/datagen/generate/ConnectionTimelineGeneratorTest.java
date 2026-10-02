package com.ahdyahmed.pymk.datagen.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.datagen.generate.ConnectionTimelineGenerator.TimestampedEdge;
import com.ahdyahmed.pymk.datagen.generate.PreferentialAttachmentGraphGenerator.Edge;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ConnectionTimelineGeneratorTest {

    @Test
    void connectionNeverPredatesEitherMember() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        Instant newestAccount = now.minusSeconds(60);

        List<TimestampedEdge> result = new ConnectionTimelineGenerator(new Random(42))
                .assignTimestamps(
                        List.of(new Edge(1, 2)),
                        now,
                        720,
                        Map.of(1L, now.minusSeconds(10_000), 2L, newestAccount));

        assertThat(result).singleElement().satisfies(edge -> {
            assertThat(edge.connectedAt()).isAfterOrEqualTo(newestAccount);
            assertThat(edge.connectedAt()).isBeforeOrEqualTo(now);
        });
    }
}
