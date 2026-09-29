package com.ahdyahmed.pymk.datagen.generate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Assigns a single {@code connectedAt} to each generated edge, once, so the
 * {@code connections} row and its matching INVITE_ACCEPTED event agree on
 * when the connection actually formed (see EventGenerator).
 */
public final class ConnectionTimelineGenerator {

    public record TimestampedEdge(PreferentialAttachmentGraphGenerator.Edge edge, Instant connectedAt) {
    }

    private final Random random;

    public ConnectionTimelineGenerator(Random random) {
        this.random = random;
    }

    public List<TimestampedEdge> assignTimestamps(
            List<PreferentialAttachmentGraphGenerator.Edge> edges, Instant now, int maxDaysAgo) {
        List<TimestampedEdge> timestamped = new ArrayList<>(edges.size());
        for (PreferentialAttachmentGraphGenerator.Edge edge : edges) {
            int daysAgo = 1 + random.nextInt(maxDaysAgo);
            timestamped.add(new TimestampedEdge(edge, now.minus(Duration.ofDays(daysAgo))));
        }
        return timestamped;
    }
}
