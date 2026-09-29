package com.ahdyahmed.pymk.datagen.generate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Barabasi-Albert preferential attachment: each new member connects to a
 * handful of existing members, chosen with probability proportional to
 * their current degree ("rich get richer"). Produces the hub-heavy degree
 * distribution real social/professional graphs have - a few very
 * well-connected members, a long tail of members with just a handful of
 * connections - which is what makes GraphWalkCandidateSource's (Day 9)
 * "friends of friends" signal meaningful instead of uniformly random.
 *
 * <p>Undirected: every accepted edge {@code (a, b)} is emitted once here.
 * The caller is responsible for writing both directed rows into
 * {@code connections} (see BulkLoader).</p>
 */
public final class PreferentialAttachmentGraphGenerator {

    public record Edge(long memberA, long memberB) {
    }

    private final int edgesPerNewMember;
    private final Random random;

    public PreferentialAttachmentGraphGenerator(int edgesPerNewMember, Random random) {
        if (edgesPerNewMember < 1) {
            throw new IllegalArgumentException("edgesPerNewMember must be >= 1");
        }
        this.edgesPerNewMember = edgesPerNewMember;
        this.random = random;
    }

    public List<Edge> generate(int memberCount) {
        List<Edge> edges = new ArrayList<>();
        if (memberCount < 2) {
            return edges;
        }

        int seedSize = Math.min(memberCount, edgesPerNewMember + 1);

        // One entry per edge-endpoint; sampling uniformly from this list is
        // equivalent to sampling a member proportional to its current degree.
        List<Long> repeatedEndpoints = new ArrayList<>();

        // Seed clique: a small fully-connected group for early members to attach to.
        for (long a = 1; a <= seedSize; a++) {
            for (long b = a + 1; b <= seedSize; b++) {
                edges.add(new Edge(a, b));
                repeatedEndpoints.add(a);
                repeatedEndpoints.add(b);
            }
        }

        for (long newMember = seedSize + 1L; newMember <= memberCount; newMember++) {
            int wanted = (int) Math.min(edgesPerNewMember, newMember - 1);
            LinkedHashSet<Long> targets = new LinkedHashSet<>();
            int attempts = 0;
            int maxAttempts = wanted * 50 + 50;
            while (targets.size() < wanted && attempts < maxAttempts) {
                long candidate = repeatedEndpoints.get(random.nextInt(repeatedEndpoints.size()));
                if (candidate != newMember) {
                    targets.add(candidate);
                }
                attempts++;
            }
            for (long target : targets) {
                edges.add(new Edge(newMember, target));
                repeatedEndpoints.add(newMember);
                repeatedEndpoints.add(target);
            }
        }

        return edges;
    }
}
