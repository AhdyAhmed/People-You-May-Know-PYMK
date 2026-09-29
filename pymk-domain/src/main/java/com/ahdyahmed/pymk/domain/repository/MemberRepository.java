package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.entity.Member;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Member lookups. The "same X, excluding me" finders back
 * {@code HeuristicCandidateSource} (Day 8) and are served by the
 * {@code idx_members_*} indexes created in V1.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findByCompany(String company);

    List<Member> findByCompanyAndIdNot(String company, Long excludeMemberId, Pageable pageable);

    List<Member> findBySchoolAndIdNot(String school, Long excludeMemberId, Pageable pageable);

    List<Member> findByGeoRegionAndIdNot(String geoRegion, Long excludeMemberId, Pageable pageable);
}
