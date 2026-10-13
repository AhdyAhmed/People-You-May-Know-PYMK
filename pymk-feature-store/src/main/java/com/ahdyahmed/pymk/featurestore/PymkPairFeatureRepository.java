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

    @Query("select max(feature.featureAsOf) from PymkPairFeature feature")
    Optional<Instant> findLatestFeatureAsOf();

    /** Removes rows superseded by a successfully materialized newer snapshot. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PymkPairFeature feature where feature.featureAsOf < :featureAsOf")
    int deleteSnapshotsOlderThan(@Param("featureAsOf") Instant featureAsOf);
}
