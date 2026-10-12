package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain CRUD on member_embeddings via JPA (Hibernate's vector module handles
 * the float[] &lt;-&gt; pgvector mapping). ANN similarity search lives
 * separately in {@link EmbeddingSearchRepository}, since the {@code <=>}
 * operator isn't something a derived-query or simple {@code @Query} method
 * expresses cleanly.
 */
public interface MemberEmbeddingRepository extends JpaRepository<MemberEmbedding, Long> {

    /** Returns an embedding only when it was available at the requested snapshot. */
    Optional<MemberEmbedding> findByMemberIdAndUpdatedAtLessThanEqual(Long memberId, Instant asOf);
}
