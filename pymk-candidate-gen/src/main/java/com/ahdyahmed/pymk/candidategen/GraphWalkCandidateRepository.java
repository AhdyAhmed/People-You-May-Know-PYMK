package com.ahdyahmed.pymk.candidategen;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded two/three-hop traversal over the symmetric connection edge list. */
@Repository
public class GraphWalkCandidateRepository {

    public record GraphCandidate(long memberId, int hopDistance, long pathCount) {
    }

    private final JdbcTemplate jdbc;

    public GraphWalkCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds candidates by shortest simple paths. Direct connections are
     * removed before limiting; path arrays prevent cycles during recursion.
     */
    public List<GraphCandidate> findCandidates(long memberId, int limit) {
        return jdbc.query("""
                WITH RECURSIVE walk(candidate_id, depth, path) AS (
                    SELECT c.connected_member_id,
                           1,
                           ARRAY[c.member_id, c.connected_member_id]::bigint[]
                    FROM connections c
                    WHERE c.member_id = ?

                    UNION ALL

                    SELECT c.connected_member_id,
                           w.depth + 1,
                           w.path || c.connected_member_id
                    FROM walk w
                    JOIN connections c ON c.member_id = w.candidate_id
                    WHERE w.depth < 3
                      AND NOT c.connected_member_id = ANY(w.path)
                ),
                min_depths AS (
                    SELECT candidate_id, MIN(depth) AS hop_distance
                    FROM walk
                    WHERE depth BETWEEN 2 AND 3
                      AND candidate_id <> ?
                      AND NOT EXISTS (
                            SELECT 1
                            FROM connections direct
                            WHERE direct.member_id = ?
                              AND direct.connected_member_id = walk.candidate_id
                      )
                    GROUP BY candidate_id
                ),
                candidate_stats AS (
                    SELECT w.candidate_id,
                           d.hop_distance,
                           COUNT(*) AS path_count
                    FROM walk w
                    JOIN min_depths d
                      ON d.candidate_id = w.candidate_id
                     AND d.hop_distance = w.depth
                    GROUP BY w.candidate_id, d.hop_distance
                )
                SELECT candidate_id, hop_distance, path_count
                FROM candidate_stats
                ORDER BY hop_distance, path_count DESC, candidate_id
                LIMIT ?
                """, (rs, rowNum) -> new GraphCandidate(
                        rs.getLong("candidate_id"),
                        rs.getInt("hop_distance"),
                        rs.getLong("path_count")),
                memberId, memberId, memberId, limit);
    }
}
