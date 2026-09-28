package com.ahdyahmed.pymk.domain.repository;

import static com.ahdyahmed.pymk.domain.support.TestData.member;
import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.support.PostgresDataJpaTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

@PostgresDataJpaTest
class MemberRepositoryTest {

    @Autowired
    MemberRepository members;

    @Test
    void savesAndLoadsMemberWithAssignedId() {
        members.saveAndFlush(member(1L, "Acme", "MIT", "Cairo"));

        Member loaded = members.findById(1L).orElseThrow();

        assertThat(loaded.getFullName()).isEqualTo("Member 1");
        assertThat(loaded.getCompany()).isEqualTo("Acme");
        assertThat(loaded.getSchool()).isEqualTo("MIT");
        assertThat(loaded.getGeoRegion()).isEqualTo("Cairo");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void sameCompanyFinderExcludesTheRequestingMemberAndHonoursLimit() {
        members.saveAll(List.of(
                member(1L, "Acme", "MIT", "Cairo"),
                member(2L, "Acme", "AUC", "Cairo"),
                member(3L, "Acme", "Cairo Univ", "Alexandria"),
                member(4L, "Globex", "MIT", "Cairo")));
        members.flush();

        List<Member> sameCompany = members.findByCompanyAndIdNot("Acme", 1L, PageRequest.of(0, 10));
        List<Member> limited = members.findByCompanyAndIdNot("Acme", 1L, PageRequest.of(0, 1));

        assertThat(sameCompany).extracting(Member::getId).containsExactlyInAnyOrder(2L, 3L);
        assertThat(limited).hasSize(1);
    }

    @Test
    void sameSchoolAndSameGeoFindersWork() {
        members.saveAll(List.of(
                member(1L, "Acme", "MIT", "Cairo"),
                member(2L, "Globex", "MIT", "Alexandria"),
                member(3L, "Initech", "AUC", "Cairo")));
        members.flush();

        assertThat(members.findBySchoolAndIdNot("MIT", 1L, PageRequest.of(0, 10)))
                .extracting(Member::getId).containsExactly(2L);
        assertThat(members.findByGeoRegionAndIdNot("Cairo", 1L, PageRequest.of(0, 10)))
                .extracting(Member::getId).containsExactly(3L);
    }
}
