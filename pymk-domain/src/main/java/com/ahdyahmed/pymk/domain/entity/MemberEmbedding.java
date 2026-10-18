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
 * generator (Day 4/5, {@code pymk-datagen}). Day 22 implements frozen profile
 * TF-IDF/SVD generation and version/fit-cutoff provenance; Day 23 owns the
 * coordinated live replacement via {@code EmbeddingRefreshJob}.</p>
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

    @Column(name = "embedding_version", nullable = false, length = 100)
    private String embeddingVersion;

    @Column(name = "fit_cutoff")
    private Instant fitCutoff;

    protected MemberEmbedding() {
        // required by JPA
    }

    public MemberEmbedding(Long memberId, float[] embedding, Instant updatedAt) {
        this(memberId, embedding, updatedAt, "placeholder-v1", null);
    }

    public MemberEmbedding(Long memberId, float[] embedding, Instant updatedAt,
                           String embeddingVersion, Instant fitCutoff) {
        if (embedding == null || embedding.length != DIMENSIONS) {
            throw new IllegalArgumentException(
                    "Embedding must have %d dimensions".formatted(DIMENSIONS));
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding values must be finite");
            }
        }
        if (memberId == null || memberId <= 0 || updatedAt == null
                || embeddingVersion == null || embeddingVersion.isBlank()
                || embeddingVersion.length() > 100
                || (fitCutoff != null && fitCutoff.isAfter(updatedAt))
                || (!"placeholder-v1".equals(embeddingVersion) && fitCutoff == null)) {
            throw new IllegalArgumentException("Invalid embedding identity or provenance");
        }
        this.memberId = memberId;
        this.embedding = embedding.clone();
        this.updatedAt = updatedAt;
        this.embeddingVersion = embeddingVersion;
        this.fitCutoff = fitCutoff;
    }

    public Long getMemberId() {
        return memberId;
    }

    public float[] getEmbedding() {
        return embedding.clone();
    }

    public String getEmbeddingVersion() { return embeddingVersion; }

    public Instant getFitCutoff() { return fitCutoff; }

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
