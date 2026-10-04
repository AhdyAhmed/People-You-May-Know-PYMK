package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.entity.Member;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Best profile-attribute matches that are not already adjacent to the
     * requesting member. Stable ordering makes source limits reproducible.
     */
    @Query("""
            select m
            from Member m
            where m.id <> :memberId
              and (
                    (:company is not null and m.company = :company)
                 or (:school is not null and m.school = :school)
                 or (:geoRegion is not null and m.geoRegion = :geoRegion)
              )
              and not exists (
                    select c.id
                    from Connection c
                    where c.memberId = :memberId
                      and c.connectedMemberId = m.id
              )
            order by (
                  case when :company is not null and m.company = :company then 1 else 0 end
                + case when :school is not null and m.school = :school then 1 else 0 end
                + case when :geoRegion is not null and m.geoRegion = :geoRegion then 1 else 0 end
            ) desc, m.id asc
            """)
    List<Member> findHeuristicCandidates(
            @Param("memberId") Long memberId,
            @Param("company") String company,
            @Param("school") String school,
            @Param("geoRegion") String geoRegion,
            Pageable pageable);
}
