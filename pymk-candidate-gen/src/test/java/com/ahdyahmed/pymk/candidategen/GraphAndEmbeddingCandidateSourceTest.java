package com.ahdyahmed.pymk.candidategen;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.candidategen.support.CandidateGenTestApplication;
import com.ahdyahmed.pymk.candidategen.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.EmbeddingSearchRepository;
import com.ahdyahmed.pymk.domain.repository.MemberEmbeddingRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = CandidateGenTestApplication.class)
@Import({
        PostgresTestConfig.class,
        CandidateEligibilityPolicy.class,
        GraphWalkCandidateRepository.class,
        GraphWalkCandidateSource.class,
        EmbeddingSearchRepository.class,
        EmbeddingRetrievalCandidateSource.class,
        ConnectionService.class
})
@Transactional
class GraphAndEmbeddingCandidateSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    MemberRepository members;

    @Autowired
    MemberEmbeddingRepository embeddings;

    @Autowired
    ConnectionRepository connectionRows;

    @Autowired
    ConnectionService connections;

    @Autowired
    GraphWalkCandidateSource graphSource;

    @Autowired
    EmbeddingRetrievalCandidateSource embeddingSource;

    @Test
    void graphWalkReturnsDeterministicShortestPathCandidates() {
        saveMembers(1, 2, 3, 4, 5, 6, 7);
        connect(1, 2);
        connect(1, 3);
        connect(2, 4);
        connect(3, 4);
        connect(2, 5);
        connect(4, 6);
        connect(5, 7);
        connectionRows.flush();

        List<CandidateHit> hits = graphSource.generate(1, 10);

        assertThat(hits).extracting(CandidateHit::candidateId)
                .containsExactly(4L, 5L, 6L, 7L);
        assertThat(hits).allMatch(hit -> hit.source() == CandidateSourceType.GRAPH_WALK);
        assertThat(hits.getFirst().metadata())
                .containsEntry("hopDistance", "2")
                .containsEntry("shortestPathCount", "2");
        assertThat(hits.get(2).metadata())
                .containsEntry("hopDistance", "3")
                .containsEntry("shortestPathCount", "2");
        assertThat(hits).extracting(CandidateHit::sourceScore)
                .isSortedAccordingTo((left, right) -> Double.compare(right, left));

        assertThat(graphSource.generate(1, 2))
                .extracting(CandidateHit::candidateId)
                .containsExactly(4L, 5L);
    }

    @Test
    void embeddingRetrievalExcludesConnectionsBeforeApplyingLimit() {
        saveMembers(10, 11, 12, 13, 14, 15);
        embeddings.saveAll(List.of(
                embedding(10, 1.0, 0.0),
                embedding(11, 0.999, 0.02),
                embedding(12, 0.98, 0.20),
                embedding(13, 0.80, 0.60),
                embedding(14, -1.0, 0.0)));
        embeddings.flush();
        connect(10, 11);
        connectionRows.flush();

        List<CandidateHit> hits = embeddingSource.generate(10, 2);

        assertThat(hits).extracting(CandidateHit::candidateId).containsExactly(12L, 13L);
        assertThat(hits).allMatch(hit -> hit.source() == CandidateSourceType.EMBEDDING);
        assertThat(hits.getFirst().sourceScore()).isGreaterThan(hits.get(1).sourceScore());
        assertThat(hits.getFirst().metadata()).containsKey("cosineDistance");
        assertThat(embeddingSource.generate(15, 5)).isEmpty();
    }

    @Test
    void absentMembersProduceNoGraphOrEmbeddingCandidates() {
        assertThat(graphSource.generate(999, 10)).isEmpty();
        assertThat(embeddingSource.generate(999, 10)).isEmpty();
    }

    private void saveMembers(long... ids) {
        for (long id : ids) {
            members.save(new Member(id, "Member " + id, "Engineer", "Acme", "MIT", "Cairo", NOW));
        }
        members.flush();
    }

    private void connect(long memberA, long memberB) {
        connections.connect(memberA, memberB, NOW.plusSeconds(memberA + memberB));
    }

    private static MemberEmbedding embedding(long memberId, double first, double second) {
        float[] vector = new float[MemberEmbedding.DIMENSIONS];
        double norm = Math.sqrt(first * first + second * second);
        vector[0] = (float) (first / norm);
        vector[1] = (float) (second / norm);
        return new MemberEmbedding(memberId, vector, NOW);
    }
}
