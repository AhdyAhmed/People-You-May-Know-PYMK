package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.vector.VectorLiterals;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ANN (approximate nearest neighbor) queries over member_embeddings using
 * pgvector's {@code <->} (cosine distance, given {@code vector_cosine_ops})
 * operator. Backs {@code EmbeddingRetrievalCandidateSource} (Day 9).
 *
 * <p>Plain JdbcTemplate rather than a Spring Data {@code @Query}: the
 * self-join form below never has to bring an embedding into application
 * memory at all, which is exactly what you want on the hot path.</p>
 */
@Repository
public class EmbeddingSearchRepository {

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
        return jdbc.queryForList("""
                SELECT e2.member_id
                FROM member_embeddings e1
                JOIN member_embeddings e2 ON e2.member_id <> e1.member_id
                WHERE e1.member_id = ?
                ORDER BY e1.embedding <-> e2.embedding
                LIMIT ?
                """, Long.class, memberId, limit);
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
                    ORDER BY embedding <-> CAST(? AS vector)
                    LIMIT ?
                    """, Long.class, excludeMemberId, literal, limit);
        }
        return jdbc.queryForList("""
                SELECT member_id
                FROM member_embeddings
                ORDER BY embedding <-> CAST(? AS vector)
                LIMIT ?
                """, Long.class, literal, limit);
    }
}
