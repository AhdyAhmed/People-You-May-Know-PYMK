package com.ahdyahmed.pymk.featurestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Day 15 contract tests against the real migrated PostgreSQL schema. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
class PymkPairFeatureRepositoryTest {

    private static final long MEMBER = 910_001L;
    private static final long CANDIDATE_ONE = 910_002L;
    private static final long CANDIDATE_TWO = 910_003L;
    private static final Instant FEATURE_AS_OF = Instant.parse("2026-09-30T00:00:00.123456Z");
    private static final Instant COMPUTED_AT = Instant.parse("2026-10-01T01:02:03.123456Z");

    @Autowired PymkPairFeatureRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seedMembers() {
        insertMember(MEMBER, "Requester");
        insertMember(CANDIDATE_ONE, "Candidate One");
        insertMember(CANDIDATE_TWO, "Candidate Two");
    }

    @Test
    void roundTripsCompositeKeySignalsAndSnapshotTimestamps() {
        PairId id = new PairId(MEMBER, CANDIDATE_ONE);
        repository.saveAndFlush(feature(id, 7, true, false, true, 0.75, 4));
        entityManager.clear();

        PymkPairFeature saved = repository.findById(id).orElseThrow();

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getMutualConnectionCount()).isEqualTo(7);
        assertThat(saved.isSameCompany()).isTrue();
        assertThat(saved.isSameSchool()).isFalse();
        assertThat(saved.isSameGeo()).isTrue();
        assertThat(saved.getEmbeddingCosineSim()).isEqualTo(0.75);
        assertThat(saved.getProfileViewCountLast30d()).isEqualTo(4);
        assertThat(saved.getFeatureAsOf()).isEqualTo(FEATURE_AS_OF);
        assertThat(saved.getComputedAt()).isEqualTo(COMPUTED_AT);
    }

    @Test
    void pairDirectionIsPreservedAndMemberLookupIsDeterministic() {
        repository.saveAllAndFlush(List.of(
                feature(new PairId(MEMBER, CANDIDATE_TWO), 1, false, false, false, -0.2, 0),
                feature(new PairId(CANDIDATE_ONE, MEMBER), 2, true, false, false, 0.4, 9),
                feature(new PairId(MEMBER, CANDIDATE_ONE), 3, true, true, false, 0.9, 1)));
        entityManager.clear();

        assertThat(repository.findById(new PairId(CANDIDATE_ONE, MEMBER))).isPresent();
        assertThat(repository.findByIdMemberIdOrderByIdCandidateId(MEMBER))
                .extracting(row -> row.getId().getCandidateId())
                .containsExactly(CANDIDATE_ONE, CANDIDATE_TWO);
    }

    @Test
    void javaContractRejectsInvalidKeysSignalsAndTemporalLeakage() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PairId(0, CANDIDATE_ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new PairId(MEMBER, MEMBER));
        assertThatIllegalArgumentException().isThrownBy(() ->
                feature(new PairId(MEMBER, CANDIDATE_ONE), -1, false, false, false, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                feature(new PairId(MEMBER, CANDIDATE_ONE), 0, false, false, false, 1.01, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PymkPairFeature(
                new PairId(MEMBER, CANDIDATE_ONE),
                0, false, false, false, 0, 0,
                COMPUTED_AT.plusSeconds(1), COMPUTED_AT));
    }

    @Test
    void databaseRejectsMissingMembers() {
        assertThatThrownBy(() -> insertRaw(MEMBER, 999_999L, FEATURE_AS_OF, COMPUTED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsSelfPairsEvenWhenJpaConstructorIsBypassed() {
        assertThatThrownBy(() -> insertRaw(MEMBER, MEMBER, FEATURE_AS_OF, COMPUTED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsFeatureSnapshotsFromAfterComputationTime() {
        assertThatThrownBy(() -> insertRaw(
                        MEMBER, CANDIDATE_ONE, COMPUTED_AT.plusSeconds(1), COMPUTED_AT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mutual_connection_count = -1",
            "profile_view_count_last_30d = -1",
            "embedding_cosine_sim = 1.01"
    })
    void databaseRejectsOutOfRangeFeatureValues(String invalidAssignment) {
        insertRaw(MEMBER, CANDIDATE_ONE, FEATURE_AS_OF, COMPUTED_AT);

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE pymk_features SET " + invalidAssignment
                                + " WHERE member_id = ? AND candidate_id = ?",
                        MEMBER,
                        CANDIDATE_ONE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PymkPairFeature feature(
            PairId id,
            int mutualCount,
            boolean sameCompany,
            boolean sameSchool,
            boolean sameGeo,
            double cosine,
            int views) {
        return new PymkPairFeature(
                id,
                mutualCount,
                sameCompany,
                sameSchool,
                sameGeo,
                cosine,
                views,
                FEATURE_AS_OF,
                COMPUTED_AT);
    }

    private void insertMember(long id, String name) {
        jdbc.update(
                "INSERT INTO members (id, full_name, created_at) VALUES (?, ?, ?)",
                id,
                name,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }

    private void insertRaw(long memberId, long candidateId, Instant featureAsOf, Instant computedAt) {
        jdbc.update("""
                INSERT INTO pymk_features (
                    member_id, candidate_id, mutual_connection_count,
                    same_company, same_school, same_geo, embedding_cosine_sim,
                    profile_view_count_last_30d, feature_as_of, computed_at
                ) VALUES (?, ?, 0, false, false, false, 0.0, 0, ?, ?)
                """,
                memberId,
                candidateId,
                Timestamp.from(featureAsOf),
                Timestamp.from(computedAt));
    }
}
