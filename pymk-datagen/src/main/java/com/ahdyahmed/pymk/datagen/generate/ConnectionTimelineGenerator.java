package com.ahdyahmed.pymk.datagen.generate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            List<PreferentialAttachmentGraphGenerator.Edge> edges,
            Instant now,
            int maxDaysAgo,
            Map<Long, Instant> memberCreatedAt) {
        List<TimestampedEdge> timestamped = new ArrayList<>(edges.size());
        for (PreferentialAttachmentGraphGenerator.Edge edge : edges) {
            Instant windowStart = now.minus(Duration.ofDays(maxDaysAgo));
            Instant participantsAvailableAt = later(
                    requiredCreationTime(memberCreatedAt, edge.memberA()),
                    requiredCreationTime(memberCreatedAt, edge.memberB()));
            Instant earliest = later(windowStart, participantsAvailableAt);
            long availableSeconds = Duration.between(earliest, now).getSeconds();
            Instant connectedAt = availableSeconds == 0
                    ? now
                    : earliest.plusSeconds(random.nextLong(availableSeconds + 1));
            timestamped.add(new TimestampedEdge(edge, connectedAt));
        }
        return timestamped;
    }

    private static Instant requiredCreationTime(Map<Long, Instant> memberCreatedAt, long memberId) {
        Instant createdAt = memberCreatedAt.get(memberId);
        if (createdAt == null) {
            throw new IllegalArgumentException("Missing creation timestamp for member " + memberId);
        }
        return createdAt;
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
