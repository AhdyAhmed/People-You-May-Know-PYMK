package com.ahdyahmed.pymk.candidategen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.ahdyahmed.pymk.candidategen.support.CandidateGenTestApplication;
import com.ahdyahmed.pymk.candidategen.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = CandidateGenTestApplication.class)
@Import({
        PostgresTestConfig.class,
        CandidateEligibilityPolicy.class,
        HeuristicCandidateSource.class,
        ConnectionService.class
})
@Transactional
class HeuristicCandidateSourceTest {

    private static final Instant JOINED_AT = Instant.parse("2025-01-01T00:00:00Z");

    @Autowired
    MemberRepository members;

    @Autowired
    ConnectionService connections;

    @Autowired
    HeuristicCandidateSource source;

    @Autowired
    CandidateEligibilityPolicy eligibility;

    @BeforeEach
    void seedMembers() {
        members.saveAll(List.of(
                member(1, "Acme", "MIT", "Cairo"),
                member(2, "Acme", "MIT", "Cairo"),
                member(3, "Acme", "AUC", "Alexandria"),
                member(4, "Globex", "MIT", "Alexandria"),
                member(5, "Initech", "AUC", "Cairo"),
                member(6, "Globex", "Yale", "London")));
        connections.connect(1, 3, JOINED_AT.plusSeconds(60));
    }

    @Test
    void ranksProfileMatchesAndExcludesSelfConnectionsAndNonMatches() {
        List<CandidateHit> hits = source.generate(1, 10);

        assertThat(hits).extracting(CandidateHit::candidateId).containsExactly(2L, 4L, 5L);
        assertThat(hits).allMatch(hit -> hit.source() == CandidateSourceType.HEURISTIC);

        CandidateHit strongest = hits.getFirst();
        assertThat(strongest.sourceScore()).isEqualTo(1.0);
        assertThat(strongest.metadata())
                .containsEntry("matchedAttributes", "company,school,geoRegion")
                .containsEntry("matchCount", "3")
                .containsEntry("company", "Acme");
        assertThat(hits.get(1).sourceScore()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void respectsLimitAfterDatabaseEligibilityFiltering() {
        assertThat(source.generate(1, 2))
                .extracting(CandidateHit::candidateId)
                .containsExactly(2L, 4L);
    }

    @Test
    void sharedPolicyRemovesSelfConnectionsDuplicatesAndMissingMembers() {
        List<CandidateHit> raw = List.of(
                hit(1),
                hit(3),
                hit(4),
                hit(999),
                hit(4));

        assertThat(eligibility.filter(1, raw))
                .extracting(CandidateHit::candidateId)
                .containsExactly(4L);
    }

    @Test
    void missingMemberHasNoCandidatesAndInvalidRequestsAreRejected() {
        assertThat(source.generate(999, 10)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> source.generate(0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> source.generate(1, 0));
    }

    private static Member member(long id, String company, String school, String geoRegion) {
        return new Member(id, "Member " + id, "Engineer", company, school, geoRegion, JOINED_AT);
    }

    private static CandidateHit hit(long candidateId) {
        return new CandidateHit(candidateId, CandidateSourceType.HEURISTIC, 1.0, Map.of());
    }
}
