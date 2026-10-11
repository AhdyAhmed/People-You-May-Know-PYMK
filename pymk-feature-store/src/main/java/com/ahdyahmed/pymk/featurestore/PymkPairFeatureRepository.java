package com.ahdyahmed.pymk.featurestore;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read/write access to materialized pair features. */
public interface PymkPairFeatureRepository extends JpaRepository<PymkPairFeature, PairId> {

    List<PymkPairFeature> findByIdMemberIdOrderByIdCandidateId(long memberId);
}
