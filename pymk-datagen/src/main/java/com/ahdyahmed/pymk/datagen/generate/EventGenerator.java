package com.ahdyahmed.pymk.datagen.generate;

import com.ahdyahmed.pymk.datagen.generate.ConnectionTimelineGenerator.TimestampedEdge;
import com.ahdyahmed.pymk.domain.entity.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Produces the member_events log: an INVITE_SENT/INVITE_ACCEPTED pair for
 * every accepted connection (so Day 18's label export has positives to
 * find), plus additional implicit-signal volume (profile views, search
 * appearances, and some ignored invites) sampled over 2-hop "friend of a
 * friend" pairs so the events look like they came from people actually
 * browsing the network rather than uniformly random strangers.
 */
public final class EventGenerator {

    public record EventRecord(long actorMemberId, long targetMemberId, EventType type, Instant occurredAt) {
    }

    private final Random random;

    public EventGenerator(Random random) {
        this.random = random;
    }

    public List<EventRecord> generate(int memberCount,
                                       List<TimestampedEdge> edges,
                                       long targetEventCount,
                                       Instant now) {
        List<EventRecord> events = new ArrayList<>();

        // 1) Every accepted connection was preceded by an invite. INVITE_ACCEPTED
        //    reuses the edge's own connectedAt so the two stay consistent.
        for (TimestampedEdge te : edges) {
            PreferentialAttachmentGraphGenerator.Edge edge = te.edge();
            Instant accepted = te.connectedAt();
            Instant sent = accepted.minus(Duration.ofDays(1 + random.nextInt(14)));
            events.add(new EventRecord(edge.memberA(), edge.memberB(), EventType.INVITE_SENT, sent));
            events.add(new EventRecord(edge.memberA(), edge.memberB(), EventType.INVITE_ACCEPTED, accepted));
        }

        // 2) Lightweight adjacency map so extra volume favors 2-hop pairs
        //    over pure noise (mirrors real "people you may actually know").
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (TimestampedEdge te : edges) {
            PreferentialAttachmentGraphGenerator.Edge edge = te.edge();
            adjacency.computeIfAbsent(edge.memberA(), k -> new ArrayList<>()).add(edge.memberB());
            adjacency.computeIfAbsent(edge.memberB(), k -> new ArrayList<>()).add(edge.memberA());
        }

        long remaining = Math.max(0, targetEventCount - events.size());
        for (long i = 0; i < remaining; i++) {
            long actor = 1 + random.nextInt(memberCount);
            long target = twoHopCandidate(actor, adjacency, memberCount);
            if (target == actor) {
                continue;
            }

            Instant occurredAt = randomPastInstant(now, 1, 720);
            double r = random.nextDouble();
            if (r < 0.60) {
                events.add(new EventRecord(actor, target, EventType.PROFILE_VIEW, occurredAt));
            } else if (r < 0.85) {
                events.add(new EventRecord(actor, target, EventType.SEARCH_APPEARANCE, occurredAt));
            } else {
                Instant ignoredAt = occurredAt.plus(Duration.ofDays(1 + random.nextInt(10)));
                events.add(new EventRecord(actor, target, EventType.INVITE_SENT, occurredAt));
                events.add(new EventRecord(actor, target, EventType.INVITE_IGNORED, ignoredAt));
            }
        }
        return events;
    }

    private long twoHopCandidate(long actor, Map<Long, List<Long>> adjacency, int memberCount) {
        List<Long> friends = adjacency.get(actor);
        if (friends != null && !friends.isEmpty()) {
            long friend = friends.get(random.nextInt(friends.size()));
            List<Long> friendsOfFriend = adjacency.get(friend);
            if (friendsOfFriend != null && !friendsOfFriend.isEmpty()) {
                return friendsOfFriend.get(random.nextInt(friendsOfFriend.size()));
            }
        }
        return 1 + random.nextInt(memberCount);
    }

    private Instant randomPastInstant(Instant now, int minDaysAgo, int maxDaysAgo) {
        int span = Math.max(1, maxDaysAgo - minDaysAgo);
        int days = minDaysAgo + random.nextInt(span);
        return now.minus(Duration.ofDays(days));
    }
}
