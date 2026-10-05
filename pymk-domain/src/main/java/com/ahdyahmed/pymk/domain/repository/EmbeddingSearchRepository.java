package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.vector.VectorLiterals;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ANN (approximate nearest neighbor) queries over member_embeddings using
 * pgvector's {@code <=>} cosine-distance operator. Backs
 * {@code EmbeddingRetrievalCandidateSource} (Day 9).
 *
 * <p>Plain JdbcTemplate rather than a Spring Data {@code @Query}: the
 * self-join form below never has to bring an embedding into application
 * memory at all, which is exactly what you want on the hot path.</p>
 */
@Repository
public class EmbeddingSearchRepository {

    /** A neighbor plus its pgvector cosine distance (smaller is closer). */
    public record EmbeddingNeighbor(long memberId, double cosineDistance) {
    }

    private final JdbcTemplate jdbc;

    public EmbeddingSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Nearest neighbors to a member's own stored embedding, excluding the
     * member itself. The embedding never leaves Postgres - {@code e1} is
     * looked up by primary key and compared against {@code e2} entirely in
     * the database.
     */
    public List<Long> findNearestNeighborIds(long memberId, int limit) {
        return findNearestNeighbors(memberId, limit).stream()
                .map(EmbeddingNeighbor::memberId)
                .toList();
    }

    /** Nearest neighbors with their cosine distance. */
    public List<EmbeddingNeighbor> findNearestNeighbors(long memberId, int limit) {
        return jdbc.query("""
                SELECT e2.member_id,
                       e1.embedding <=> e2.embedding AS cosine_distance
                FROM member_embeddings e1
                JOIN member_embeddings e2 ON e2.member_id <> e1.member_id
                WHERE e1.member_id = ?
                ORDER BY cosine_distance
                LIMIT ?
                """, (rs, rowNum) -> new EmbeddingNeighbor(
                        rs.getLong("member_id"),
                        rs.getDouble("cosine_distance")), memberId, limit);
    }

    /**
     * Nearest neighbors excluding first-degree connections before applying
     * the limit, so eligibility filtering cannot under-fill the result page.
     */
    public List<EmbeddingNeighbor> findNearestUnconnectedNeighbors(long memberId, int limit) {
        return jdbc.query("""
                SELECT e2.member_id,
                       e1.embedding <=> e2.embedding AS cosine_distance
                FROM member_embeddings e1
                JOIN member_embeddings e2 ON e2.member_id <> e1.member_id
                WHERE e1.member_id = ?
                  AND NOT EXISTS (
                        SELECT 1
                        FROM connections c
                        WHERE c.member_id = e1.member_id
                          AND c.connected_member_id = e2.member_id
                  )
                ORDER BY cosine_distance
                LIMIT ?
                """, (rs, rowNum) -> new EmbeddingNeighbor(
                        rs.getLong("member_id"),
                        rs.getDouble("cosine_distance")), memberId, limit);
    }

    /**
     * Nearest neighbors to an arbitrary embedding vector that may not be
     * persisted yet (e.g. a freshly computed candidate embedding). Formats
     * the vector as a text literal and casts it server-side; see
     * {@link VectorLiterals}.
     */
    public List<Long> findNearestNeighborIds(float[] vector, Long excludeMemberId, int limit) {
        String literal = VectorLiterals.toLiteral(vector);
        if (excludeMemberId != null) {
            return jdbc.queryForList("""
                    SELECT member_id
                    FROM member_embeddings
                    WHERE member_id <> ?
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """, Long.class, excludeMemberId, literal, limit);
        }
        return jdbc.queryForList("""
                SELECT member_id
                FROM member_embeddings
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, Long.class, literal, limit);
    }
}
