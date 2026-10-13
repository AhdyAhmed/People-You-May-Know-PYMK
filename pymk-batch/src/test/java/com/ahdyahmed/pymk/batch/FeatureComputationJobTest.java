package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Full Day 17 job run against migrated PostgreSQL/pgvector and Batch metadata. */
@SpringBatchTest
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "pymk.batch.feature-computation.chunk-size=2",
        "pymk.batch.feature-computation.fetch-size=2",
        "pymk.batch.feature-computation.graph-candidate-limit=10",
        "pymk.batch.feature-computation.heuristic-candidate-limit=10",
        "pymk.batch.feature-computation.embedding-candidate-limit=10",
        "pymk.batch.feature-computation.global-candidate-limit=20"
})
@Import(PostgresTestConfig.class)
class FeatureComputationJobTest {

    private static final long REQUESTER = 930_001L;
    private static final long CANDIDATE = 930_002L;
    private static final long MUTUAL = 930_003L;
    private static final long DIRECT = 930_004L;
    private static final long FUTURE = 930_005L;
    private static final Instant AS_OF = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant COMPUTED_AT = AS_OF.plus(10, ChronoUnit.MINUTES);

    @Autowired JobLauncherTestUtils jobs;
    @Autowired SnapshotCandidateRepository candidates;
    @Autowired JobParametersValidator featureComputationJobParametersValidator;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;
    @Autowired MemberEventRepository events;
    @Autowired MemberEmbeddingRepository embeddings;
    @Autowired PymkPairFeatureRepository features;

    @BeforeEach
    void seedSnapshot() {
        features.deleteAllInBatch();
        embeddings.deleteAllInBatch();
        events.deleteAllInBatch();
        connections.deleteAllInBatch();
        members.deleteAllInBatch();

        Instant createdAt = AS_OF.minus(365, ChronoUnit.DAYS);
        members.saveAllAndFlush(List.of(
                member(REQUESTER, "Requester", "Acme", "School A", "Cairo", createdAt),
                member(CANDIDATE, "Candidate", "Acme", "School B", "Cairo", createdAt),
                member(MUTUAL, "Mutual", "Other", "School C", "Giza", createdAt),
                member(DIRECT, "Direct", "Acme", "School D", "Alexandria", createdAt),
                member(FUTURE, "Future", "Acme", "School A", "Cairo", AS_OF.plusSeconds(1))));

        connect(REQUESTER, MUTUAL, AS_OF.minus(30, ChronoUnit.DAYS));
        connect(CANDIDATE, MUTUAL, AS_OF.minus(20, ChronoUnit.DAYS));
        connect(REQUESTER, DIRECT, AS_OF.minus(10, ChronoUnit.DAYS));

        events.saveAllAndFlush(List.of(
                new MemberEvent(REQUESTER, CANDIDATE, EventType.PROFILE_VIEW,
                        AS_OF.minus(5, ChronoUnit.DAYS)),
                new MemberEvent(REQUESTER, CANDIDATE, EventType.PROFILE_VIEW, AS_OF),
                new MemberEvent(REQUESTER, CANDIDATE, EventType.PROFILE_VIEW,
                        AS_OF.plusSeconds(1))));

        embeddings.saveAllAndFlush(List.of(
                embedding(REQUESTER, 1.0f, 0.0f),
                embedding(CANDIDATE, 0.6f, 0.8f),
                embedding(MUTUAL, 0.0f, 1.0f),
                embedding(DIRECT, -1.0f, 0.0f)));

        features.saveAndFlush(new PymkPairFeature(
                new PairId(REQUESTER, DIRECT),
                0,
                true,
                false,
                false,
                -1.0,
                0,
                AS_OF.minus(1, ChronoUnit.DAYS),
                AS_OF.minus(1, ChronoUnit.DAYS)));
    }

    @Test
    void materializesSnapshotUpsertsOnRerunAndDeletesOnlyStaleRowsAfterSuccess()
            throws Exception {
        assertThat(candidates.findCandidates(REQUESTER, AS_OF))
                .contains(CANDIDATE)
                .doesNotContain(REQUESTER, DIRECT, FUTURE);

        JobExecution firstRun = jobs.launchJob(parameters(1));

        assertThat(firstRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(firstRun.getStepExecutions())
                .extracting(execution -> execution.getStepName())
                .containsExactlyInAnyOrder(
                        "validateFeatureSnapshotStep",
                        "computePairFeaturesStep",
                        "deleteStaleFeatureSnapshotsStep");

        PymkPairFeature result = features.findById(new PairId(REQUESTER, CANDIDATE))
                .orElseThrow();
        assertThat(result.getMutualConnectionCount()).isEqualTo(1);
        assertThat(result.isSameCompany()).isTrue();
        assertThat(result.isSameSchool()).isFalse();
        assertThat(result.isSameGeo()).isTrue();
        assertThat(result.getEmbeddingCosineSim()).isCloseTo(0.6,
                org.assertj.core.data.Offset.offset(1.0e-6));
        assertThat(result.getProfileViewCountLast30d()).isEqualTo(2);
        assertThat(result.getFeatureAsOf()).isEqualTo(AS_OF);
        assertThat(result.getComputedAt()).isEqualTo(COMPUTED_AT);
        assertThat(features.findById(new PairId(REQUESTER, DIRECT))).isEmpty();
        assertThat(features.findAll()).allSatisfy(row ->
                assertThat(row.getFeatureAsOf()).isEqualTo(AS_OF));

        long rowCount = features.count();
        JobExecution rerun = jobs.launchJob(parameters(2));
        assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(features.count()).isEqualTo(rowCount);

        assertThatThrownBy(() -> FeatureComputationJobConfig.validateMonotonicSnapshot(
                        AS_OF.minusSeconds(1), Optional.of(AS_OF)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("older than materialized snapshot");
        assertThat(features.count()).isEqualTo(rowCount);
        assertThat(features.findAll()).allSatisfy(row ->
                assertThat(row.getFeatureAsOf()).isEqualTo(AS_OF));
    }

    @Test
    void rejectsMissingMalformedAndFutureSnapshotParameters() {
        assertThatThrownBy(() -> featureComputationJobParametersValidator.validate(
                        new JobParameters()))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining(FeatureComputationJobConfig.FEATURE_AS_OF);

        JobParameters malformed = new JobParametersBuilder()
                .addString(FeatureComputationJobConfig.FEATURE_AS_OF, "not-an-instant")
                .toJobParameters();
        assertThatThrownBy(() -> featureComputationJobParametersValidator.validate(malformed))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("ISO-8601");

        JobParameters futureSnapshot = new JobParametersBuilder()
                .addString(FeatureComputationJobConfig.FEATURE_AS_OF,
                        COMPUTED_AT.plusSeconds(1).toString())
                .toJobParameters();
        assertThatThrownBy(() -> featureComputationJobParametersValidator.validate(futureSnapshot))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("must not be in the future");
    }

    private JobParameters parameters(long runId) {
        return new JobParametersBuilder()
                .addString(FeatureComputationJobConfig.FEATURE_AS_OF, AS_OF.toString())
                .addLong("run.id", runId)
                .toJobParameters();
    }

    private void connect(long first, long second, Instant connectedAt) {
        connections.saveAllAndFlush(List.of(
                new Connection(first, second, connectedAt),
                new Connection(second, first, connectedAt)));
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

    private static MemberEmbedding embedding(long memberId, float first, float second) {
        float[] vector = new float[MemberEmbedding.DIMENSIONS];
        vector[0] = first;
        vector[1] = second;
        return new MemberEmbedding(memberId, vector, AS_OF.minusSeconds(1));
    }
}
