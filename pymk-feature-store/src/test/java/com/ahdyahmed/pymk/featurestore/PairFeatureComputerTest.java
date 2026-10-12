package com.ahdyahmed.pymk.featurestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahdyahmed.pymk.domain.entity.Connection;
import com.ahdyahmed.pymk.domain.entity.EventType;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import com.ahdyahmed.pymk.domain.entity.MemberEvent;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberEmbeddingRepository;
import com.ahdyahmed.pymk.domain.repository.MemberEventRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/** Day 16 feature computation against real PostgreSQL and pgvector mappings. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, PairFeatureComputer.class})
class PairFeatureComputerTest {

    private static final long MEMBER = 920_001L;
    private static final long CANDIDATE = 920_002L;
    private static final long MUTUAL_BEFORE = 920_003L;
    private static final long MUTUAL_AFTER = 920_004L;
    private static final long FUTURE_MEMBER = 920_005L;
    private static final Instant AS_OF = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant COMPUTED_AT = AS_OF.plus(5, ChronoUnit.MINUTES);

    @Autowired PairFeatureComputer computer;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;
    @Autowired MemberEventRepository events;
    @Autowired MemberEmbeddingRepository embeddings;

    @BeforeEach
    void seedMembers() {
        Instant createdAt = AS_OF.minus(365, ChronoUnit.DAYS);
        members.saveAllAndFlush(List.of(
                member(MEMBER, "Requester", "Acme", "School A", "Cairo", createdAt),
                member(CANDIDATE, "Candidate", "Acme", "School B", "Cairo", createdAt),
                member(MUTUAL_BEFORE, "Old mutual", null, null, null, createdAt),
                member(MUTUAL_AFTER, "Future mutual", null, null, null, createdAt),
                member(FUTURE_MEMBER, "Future member", "Acme", "School A", "Cairo",
                        AS_OF.plusSeconds(1))));
    }

    @Test
    void computesEveryFeatureWithDirectionalSnapshotAndWindowBoundaries() {
        Instant beforeSnapshot = AS_OF.minusSeconds(1);
        Instant afterSnapshot = AS_OF.plusSeconds(1);
        connectToSharedNeighbor(MEMBER, CANDIDATE, MUTUAL_BEFORE, beforeSnapshot);
        connectToSharedNeighbor(MEMBER, CANDIDATE, MUTUAL_AFTER, afterSnapshot);

        events.saveAllAndFlush(List.of(
                profileView(MEMBER, CANDIDATE, AS_OF.minus(30, ChronoUnit.DAYS)),
                profileView(MEMBER, CANDIDATE, AS_OF.minus(30, ChronoUnit.DAYS).plusNanos(1)),
                profileView(MEMBER, CANDIDATE, AS_OF),
                profileView(MEMBER, CANDIDATE, AS_OF.plusNanos(1)),
                profileView(CANDIDATE, MEMBER, AS_OF.minusSeconds(1)),
                new MemberEvent(MEMBER, CANDIDATE, EventType.SEARCH_APPEARANCE,
                        AS_OF.minusSeconds(1))));

        embeddings.saveAllAndFlush(List.of(
                embedding(MEMBER, 1.0f, 0.0f, beforeSnapshot),
                embedding(CANDIDATE, 0.6f, 0.8f, beforeSnapshot)));

        PymkPairFeature result = computer.compute(
                new PairId(MEMBER, CANDIDATE), AS_OF, COMPUTED_AT);

        assertThat(result.getMutualConnectionCount()).isEqualTo(1);
        assertThat(result.isSameCompany()).isTrue();
        assertThat(result.isSameSchool()).isFalse();
        assertThat(result.isSameGeo()).isTrue();
        assertThat(result.getEmbeddingCosineSim()).isCloseTo(0.6, withinTolerance());
        assertThat(result.getProfileViewCountLast30d()).isEqualTo(2);
        assertThat(result.getFeatureAsOf()).isEqualTo(AS_OF);
        assertThat(result.getComputedAt()).isEqualTo(COMPUTED_AT);
    }

    @Test
    void futureOrMissingEmbeddingsProduceNeutralSimilarityWithoutLeakage() {
        embeddings.saveAllAndFlush(List.of(
                embedding(MEMBER, 1.0f, 0.0f, AS_OF.minusSeconds(1)),
                embedding(CANDIDATE, 1.0f, 0.0f, AS_OF.plusSeconds(1))));

        PymkPairFeature withFutureCandidateEmbedding = computer.compute(
                new PairId(MEMBER, CANDIDATE), AS_OF, COMPUTED_AT);
        assertThat(withFutureCandidateEmbedding.getEmbeddingCosineSim()).isZero();

        embeddings.deleteById(CANDIDATE);
        PymkPairFeature withMissingCandidateEmbedding = computer.compute(
                new PairId(MEMBER, CANDIDATE), AS_OF, COMPUTED_AT);
        assertThat(withMissingCandidateEmbedding.getEmbeddingCosineSim()).isZero();
    }

    @Test
    void rejectsMissingOrNotYetCreatedMembersAndFutureSnapshots() {
        assertThatThrownBy(() -> computer.compute(
                        new PairId(MEMBER, 999_999L), AS_OF, COMPUTED_AT))
                .isInstanceOf(FeatureInputUnavailableException.class)
                .hasMessageContaining("does not exist");

        assertThatThrownBy(() -> computer.compute(
                        new PairId(MEMBER, FUTURE_MEMBER), AS_OF, COMPUTED_AT))
                .isInstanceOf(FeatureInputUnavailableException.class)
                .hasMessageContaining("did not exist at snapshot");

        assertThatIllegalArgumentException().isThrownBy(() -> computer.compute(
                new PairId(MEMBER, CANDIDATE), COMPUTED_AT.plusSeconds(1), COMPUTED_AT));
    }

    private void connectToSharedNeighbor(
            long memberId, long candidateId, long mutualId, Instant connectedAt) {
        connections.saveAllAndFlush(List.of(
                new Connection(memberId, mutualId, connectedAt),
                new Connection(mutualId, memberId, connectedAt),
                new Connection(candidateId, mutualId, connectedAt),
                new Connection(mutualId, candidateId, connectedAt)));
    }

    private static Member member(
            long id,
            String name,
            String company,
            String school,
            String geo,
            Instant createdAt) {
        return new Member(id, name, null, company, school, geo, createdAt);
    }

    private static MemberEvent profileView(long actorId, long targetId, Instant occurredAt) {
        return new MemberEvent(actorId, targetId, EventType.PROFILE_VIEW, occurredAt);
    }

    private static MemberEmbedding embedding(
            long memberId, float first, float second, Instant updatedAt) {
        float[] vector = new float[MemberEmbedding.DIMENSIONS];
        vector[0] = first;
        vector[1] = second;
        return new MemberEmbedding(memberId, vector, updatedAt);
    }

    private static org.assertj.core.data.Offset<Double> withinTolerance() {
        return org.assertj.core.data.Offset.offset(1.0e-6);
    }
}
