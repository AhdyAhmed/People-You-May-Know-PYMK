package com.ahdyahmed.pymk.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.domain.support.PostgresDataJpaTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Backs the design claim in PYMK_DESIGN.md 5.2: a symmetric edge list gives
 * fast adjacency lookups. Loads a mid-sized random graph, then checks the
 * query plan uses an index and that lookups stay quick.
 *
 * <p>The plan assertion is the meaningful one (it cannot flake on a slow CI
 * runner). The latency bound is deliberately generous, it exists to catch a
 * missing index, not to benchmark hardware.</p>
 */
@PostgresDataJpaTest
class ConnectionAdjacencyScaleTest {

    private static final Logger log = LoggerFactory.getLogger(ConnectionAdjacencyScaleTest.class);

    private static final int MEMBERS = 5_000;
    private static final int TARGET_DEGREE = 10;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConnectionRepository connections;

    @BeforeEach
    void loadGraph() {
        List<Object[]> memberRows = new ArrayList<>();
        for (long id = 1; id <= MEMBERS; id++) {
            memberRows.add(new Object[] {id, "Member " + id});
        }
        jdbc.batchUpdate("INSERT INTO members (id, full_name) VALUES (?, ?)", memberRows);

        // Deterministic random graph, one undirected pair stored as two rows.
        Random random = new Random(42);
        Set<Long> seenPairs = new HashSet<>();
        List<Object[]> edgeRows = new ArrayList<>();
        for (long a = 1; a <= MEMBERS; a++) {
            for (int i = 0; i < TARGET_DEGREE / 2; i++) {
                long b = 1 + random.nextInt(MEMBERS);
                if (a == b) {
                    continue;
                }
                long lo = Math.min(a, b);
                long hi = Math.max(a, b);
                if (seenPairs.add(lo * (MEMBERS + 1L) + hi)) {
                    edgeRows.add(new Object[] {lo, hi});
                    edgeRows.add(new Object[] {hi, lo});
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO connections (member_id, connected_member_id) VALUES (?, ?)", edgeRows);
        jdbc.execute("ANALYZE connections");
        log.info("Loaded {} members and {} directed edge rows", MEMBERS, edgeRows.size());
    }

    @Test
    void everyEdgeHasItsReverseRow() {
        Long unmatched = jdbc.queryForObject("""
                SELECT COUNT(*) FROM connections c
                WHERE NOT EXISTS (
                    SELECT 1 FROM connections r
                    WHERE r.member_id = c.connected_member_id
                      AND r.connected_member_id = c.member_id)
                """, Long.class);

        assertThat(unmatched).isZero();
    }

    @Test
    void adjacencyLookupUsesAnIndexNotASequentialScan() {
        List<String> plan = jdbc.queryForList(
                "EXPLAIN SELECT connected_member_id FROM connections WHERE member_id = 1234",
                String.class);
        String planText = String.join("\n", plan);
        log.info("Adjacency lookup plan:\n{}", planText);

        assertThat(planText).doesNotContain("Seq Scan on connections");
        assertThat(planText).containsIgnoringCase("index");
    }

    @Test
    void adjacencyLookupsAreFast() {
        Random random = new Random(7);

        // Warm up JPA / connection pool so the timing reflects the query.
        for (int i = 0; i < 100; i++) {
            connections.findConnectedMemberIds(1L + random.nextInt(MEMBERS));
        }

        int lookups = 1_000;
        long start = System.nanoTime();
        long totalNeighbors = 0;
        for (int i = 0; i < lookups; i++) {
            totalNeighbors += connections.findConnectedMemberIds(1L + random.nextInt(MEMBERS)).size();
        }
        double avgMs = (System.nanoTime() - start) / 1_000_000.0 / lookups;
        log.info("{} adjacency lookups: avg {} ms, avg degree {}",
                lookups, String.format("%.3f", avgMs), totalNeighbors / lookups);

        assertThat(totalNeighbors).isPositive();
        assertThat(avgMs).isLessThan(50.0);
    }
}
