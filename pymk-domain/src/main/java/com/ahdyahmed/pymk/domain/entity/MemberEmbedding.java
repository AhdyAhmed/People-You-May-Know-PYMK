package com.ahdyahmed.pymk.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A member's embedding vector for the L0 embedding-retrieval candidate
 * source (Day 9) and the embeddingCosineSim feature (Day 15/16). Matches
 * PYMK_DESIGN.md section 5.1.
 *
 * <p>{@code memberId} is both the primary key and (enforced at the database
 * level, see V2 migration) a foreign key to {@code members.id} - there is no
 * JPA {@code @OneToOne} to {@link Member} here, matching this codebase's
 * existing convention of plain {@code Long} ids rather than mapped
 * relationships (see {@link Connection}, {@link MemberEvent}).</p>
 *
 * <p>Populated with random placeholder vectors by the synthetic data
 * generator (Day 4/5, {@code pymk-datagen}); real embeddings (node2vec over
 * the connection graph, or profile-text embeddings) replace them in Week 4
 * via {@code EmbeddingRefreshJob}.</p>
 */
@Entity
@Table(name = "member_embeddings")
public class MemberEmbedding {

    /** Must match the `vector(128)` column type declared in the V2 migration. */
    public static final int DIMENSIONS = 128;

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MemberEmbedding() {
        // required by JPA
    }

    public MemberEmbedding(Long memberId, float[] embedding, Instant updatedAt) {
        if (embedding.length != DIMENSIONS) {
            throw new IllegalArgumentException(
                    "Embedding must have %d dimensions, got %d".formatted(DIMENSIONS, embedding.length));
        }
        this.memberId = memberId;
        this.embedding = embedding;
        this.updatedAt = updatedAt;
    }

    public Long getMemberId() {
        return memberId;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemberEmbedding that)) return false;
        return Objects.equals(memberId, that.memberId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(memberId);
    }

    @Override
    public String toString() {
        return "MemberEmbedding{memberId=%s, dimensions=%d, updatedAt=%s}"
                .formatted(memberId, embedding == null ? 0 : embedding.length, updatedAt);
    }
}
