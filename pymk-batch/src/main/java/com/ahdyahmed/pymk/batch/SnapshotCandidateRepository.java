package com.ahdyahmed.pymk.batch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded L0-style candidate retrieval using only data available at a snapshot. */
@Repository
public class SnapshotCandidateRepository {

    private final JdbcTemplate jdbc;
    private final FeatureComputationProperties properties;

    public SnapshotCandidateRepository(
            JdbcTemplate jdbc, FeatureComputationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public List<Long> findCandidates(long memberId, Instant featureAsOf) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        Objects.requireNonNull(featureAsOf, "featureAsOf must not be null");
        properties.validate();

        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        addUntilFull(merged, graphCandidates(memberId, featureAsOf));
        addUntilFull(merged, heuristicCandidates(memberId, featureAsOf));
        addUntilFull(merged, embeddingCandidates(memberId, featureAsOf));
        return List.copyOf(merged);
    }

    private List<Long> graphCandidates(long memberId, Instant asOf) {
        Timestamp snapshot = Timestamp.from(asOf);
        return jdbc.queryForList("""
                WITH RECURSIVE walk(candidate_id, depth, path) AS (
                    SELECT c.connected_member_id,
                           1,
                           ARRAY[c.member_id, c.connected_member_id]::bigint[]
                    FROM connections c
                    WHERE c.member_id = ?
                      AND c.connected_at <= ?

                    UNION ALL

                    SELECT c.connected_member_id,
                           walk.depth + 1,
                           walk.path || c.connected_member_id
                    FROM walk
                    JOIN connections c ON c.member_id = walk.candidate_id
                    WHERE walk.depth < 3
                      AND c.connected_at <= ?
                      AND NOT c.connected_member_id = ANY(walk.path)
                ),
                min_depths AS (
                    SELECT walk.candidate_id, MIN(walk.depth) AS hop_distance
                    FROM walk
                    JOIN members candidate ON candidate.id = walk.candidate_id
                    WHERE walk.depth BETWEEN 2 AND 3
                      AND walk.candidate_id <> ?
                      AND candidate.created_at <= ?
                      AND NOT EXISTS (
                            SELECT 1
                            FROM connections direct
                            WHERE direct.member_id = ?
                              AND direct.connected_member_id = walk.candidate_id
                              AND direct.connected_at <= ?
                      )
                    GROUP BY walk.candidate_id
                ),
                candidate_stats AS (
                    SELECT walk.candidate_id,
                           min_depths.hop_distance,
                           COUNT(*) AS path_count
                    FROM walk
                    JOIN min_depths
                      ON min_depths.candidate_id = walk.candidate_id
                     AND min_depths.hop_distance = walk.depth
                    GROUP BY walk.candidate_id, min_depths.hop_distance
                )
                SELECT candidate_id
                FROM candidate_stats
                ORDER BY hop_distance, path_count DESC, candidate_id
                LIMIT ?
                """, Long.class,
                memberId,
                snapshot,
                snapshot,
                memberId,
                snapshot,
                memberId,
                snapshot,
                properties.getGraphCandidateLimit());
    }

    private List<Long> heuristicCandidates(long memberId, Instant asOf) {
        Timestamp snapshot = Timestamp.from(asOf);
        return jdbc.queryForList("""
                SELECT candidate.id
                FROM members requester
                JOIN members candidate ON candidate.id <> requester.id
                WHERE requester.id = ?
                  AND candidate.created_at <= ?
                  AND (
                        (NULLIF(BTRIM(requester.company), '') IS NOT NULL
                            AND candidate.company = requester.company)
                     OR (NULLIF(BTRIM(requester.school), '') IS NOT NULL
                            AND candidate.school = requester.school)
                     OR (NULLIF(BTRIM(requester.geo_region), '') IS NOT NULL
                            AND candidate.geo_region = requester.geo_region)
                  )
                  AND NOT EXISTS (
                        SELECT 1
                        FROM connections direct
                        WHERE direct.member_id = requester.id
                          AND direct.connected_member_id = candidate.id
                          AND direct.connected_at <= ?
                  )
                ORDER BY (
                      CASE WHEN NULLIF(BTRIM(requester.company), '') IS NOT NULL
                                  AND candidate.company = requester.company THEN 1 ELSE 0 END
                    + CASE WHEN NULLIF(BTRIM(requester.school), '') IS NOT NULL
                                  AND candidate.school = requester.school THEN 1 ELSE 0 END
                    + CASE WHEN NULLIF(BTRIM(requester.geo_region), '') IS NOT NULL
                                  AND candidate.geo_region = requester.geo_region THEN 1 ELSE 0 END
                ) DESC, candidate.id
                LIMIT ?
                """, Long.class,
                memberId,
                snapshot,
                snapshot,
                properties.getHeuristicCandidateLimit());
    }

    private List<Long> embeddingCandidates(long memberId, Instant asOf) {
        Timestamp snapshot = Timestamp.from(asOf);
        return jdbc.queryForList("""
                SELECT candidate_embedding.member_id
                FROM member_embeddings requester_embedding
                JOIN member_embeddings candidate_embedding
                  ON candidate_embedding.member_id <> requester_embedding.member_id
                JOIN members candidate ON candidate.id = candidate_embedding.member_id
                WHERE requester_embedding.member_id = ?
                  AND requester_embedding.updated_at <= ?
                  AND candidate_embedding.updated_at <= ?
                  AND requester_embedding.embedding_version = candidate_embedding.embedding_version
                  AND requester_embedding.fit_cutoff IS NOT DISTINCT FROM candidate_embedding.fit_cutoff
                  AND vector_norm(requester_embedding.embedding) > 0
                  AND vector_norm(candidate_embedding.embedding) > 0
                  AND candidate.created_at <= ?
                  AND NOT EXISTS (
                        SELECT 1
                        FROM connections direct
                        WHERE direct.member_id = requester_embedding.member_id
                          AND direct.connected_member_id = candidate_embedding.member_id
                          AND direct.connected_at <= ?
                  )
                ORDER BY requester_embedding.embedding <=> candidate_embedding.embedding,
                         candidate_embedding.member_id
                LIMIT ?
                """, Long.class,
                memberId,
                snapshot,
                snapshot,
                snapshot,
                snapshot,
                properties.getEmbeddingCandidateLimit());
    }

    private void addUntilFull(LinkedHashSet<Long> merged, List<Long> candidates) {
        for (Long candidate : candidates) {
            if (merged.size() >= properties.getGlobalCandidateLimit()) {
                return;
            }
            merged.add(candidate);
        }
    }
}
