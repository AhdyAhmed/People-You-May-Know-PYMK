package com.ahdyahmed.pymk.datagen.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.datagen.generate.PreferentialAttachmentGraphGenerator.Edge;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure in-memory checks (no Postgres needed) that the generated graph is a
 * valid simple graph and actually has the hub-heavy shape the design calls
 * for, not just "some randomness".
 */
class PreferentialAttachmentGraphGeneratorTest {

    @Test
    void generatesASimpleGraphWithNoSelfLoopsOrDuplicateEdges() {
        List<Edge> edges = new PreferentialAttachmentGraphGenerator(6, new Random(42)).generate(2_000);

        Set<Long> seen = new HashSet<>();
        for (Edge e : edges) {
            assertThat(e.memberA()).isNotEqualTo(e.memberB());
            long key = Math.min(e.memberA(), e.memberB()) * 1_000_000L + Math.max(e.memberA(), e.memberB());
            assertThat(seen.add(key)).as("duplicate edge %s", e).isTrue();
        }
    }

    @Test
    void everyMemberEndsUpConnected() {
        int memberCount = 2_000;
        List<Edge> edges = new PreferentialAttachmentGraphGenerator(6, new Random(42)).generate(memberCount);

        Set<Long> connectedMembers = new HashSet<>();
        for (Edge e : edges) {
            connectedMembers.add(e.memberA());
            connectedMembers.add(e.memberB());
        }
        assertThat(connectedMembers).hasSize(memberCount);
    }

    @Test
    void degreeDistributionIsHubHeavyNotUniform() {
        int memberCount = 5_000;
        List<Edge> edges = new PreferentialAttachmentGraphGenerator(6, new Random(42)).generate(memberCount);

        Map<Long, Integer> degree = new HashMap<>();
        for (Edge e : edges) {
            degree.merge(e.memberA(), 1, Integer::sum);
            degree.merge(e.memberB(), 1, Integer::sum);
        }

        double avgDegree = degree.values().stream().mapToInt(Integer::intValue).average().orElseThrow();
        int maxDegree = degree.values().stream().mapToInt(Integer::intValue).max().orElseThrow();

        // A uniform-random graph's max degree stays close to the average.
        // Preferential attachment should produce hubs well above it.
        assertThat(maxDegree).isGreaterThan((int) (avgDegree * 5));
    }

    @Test
    void sameSeedProducesTheSameGraph() {
        List<Edge> first = new PreferentialAttachmentGraphGenerator(6, new Random(7)).generate(500);
        List<Edge> second = new PreferentialAttachmentGraphGenerator(6, new Random(7)).generate(500);

        assertThat(first).isEqualTo(second);
    }
}
