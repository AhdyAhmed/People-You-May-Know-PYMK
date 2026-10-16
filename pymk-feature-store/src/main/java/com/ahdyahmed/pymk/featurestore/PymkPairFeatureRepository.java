package com.ahdyahmed.pymk.featurestore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read/write access to materialized pair features. */
public interface PymkPairFeatureRepository extends JpaRepository<PymkPairFeature, PairId> {

    List<PymkPairFeature> findByIdMemberIdOrderByIdCandidateId(long memberId);

    /** Fetches the serving feature rows for one L0 batch without an N+1 query. */
    @Query("""
            select feature from PymkPairFeature feature
            where feature.id.memberId = :memberId
              and feature.id.candidateId in :candidateIds
            """)
    List<PymkPairFeature> findForCandidates(
            @Param("memberId") long memberId,
            @Param("candidateIds") List<Long> candidateIds);

    @Query("select max(feature.featureAsOf) from PymkPairFeature feature")
    Optional<Instant> findLatestFeatureAsOf();

    /** Removes rows superseded by a successfully materialized newer snapshot. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PymkPairFeature feature where feature.featureAsOf < :featureAsOf")
    int deleteSnapshotsOlderThan(@Param("featureAsOf") Instant featureAsOf);
}
