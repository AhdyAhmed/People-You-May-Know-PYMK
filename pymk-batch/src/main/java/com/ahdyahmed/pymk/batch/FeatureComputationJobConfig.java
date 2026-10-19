package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.featurestore.PairFeatureComputer;
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring Batch wiring for the restartable Day 17 feature materialization job. */
@Configuration
public class FeatureComputationJobConfig {

    public static final String JOB_NAME = "featureComputationJob";
    public static final String FEATURE_AS_OF = "featureAsOf";

    @Bean
    Clock batchClock() {
        return Clock.systemUTC();
    }

    @Bean
    Job featureComputationJob(
            JobRepository jobRepository,
            JobParametersValidator featureComputationJobParametersValidator,
            Step validateFeatureSnapshotStep,
            Step computePairFeaturesStep,
            Step deleteStaleFeatureSnapshotsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(featureComputationJobParametersValidator)
                .start(validateFeatureSnapshotStep)
                .next(computePairFeaturesStep)
                .next(deleteStaleFeatureSnapshotsStep)
                .build();
    }

    @Bean
    JobParametersValidator featureComputationJobParametersValidator(Clock batchClock) {
        return parameters -> {
            Instant featureAsOf = parse(parameters, FEATURE_AS_OF);
            if (featureAsOf.isAfter(batchClock.instant())) {
                throw new JobParametersInvalidException(
                        FEATURE_AS_OF + " must not be in the future");
            }
        };
    }

    @Bean
    Step validateFeatureSnapshotStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            org.springframework.batch.core.step.tasklet.Tasklet validateFeatureSnapshotTasklet) {
        return new StepBuilder("validateFeatureSnapshotStep", jobRepository)
                .tasklet(validateFeatureSnapshotTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    org.springframework.batch.core.step.tasklet.Tasklet validateFeatureSnapshotTasklet(
            PymkPairFeatureRepository features,
            JdbcTemplate jdbc,
            @Value("#{jobParameters['featureAsOf']}") String featureAsOfParameter) {
        Instant featureAsOf = JobParameterInstants.required(featureAsOfParameter, FEATURE_AS_OF);
        return (contribution, chunkContext) -> {
            if (jdbc.queryForObject("SELECT release_id IS NOT NULL FROM pymk_active_release", Boolean.class)) {
                throw new IllegalStateException("A managed release is active; use embeddingRefreshJob to refresh serving features");
            }
            validateMonotonicSnapshot(featureAsOf, features.findLatestFeatureAsOf());
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step computePairFeaturesStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<Long> activeMemberReader,
            PairFeatureItemProcessor pairFeatureProcessor,
            ItemWriter<List<PymkPairFeature>> pairFeatureWriter,
            FeatureComputationProperties properties) {
        properties.validate();
        return new StepBuilder("computePairFeaturesStep", jobRepository)
                .<Long, List<PymkPairFeature>>chunk(properties.getChunkSize(), transactionManager)
                .reader(activeMemberReader)
                .processor(pairFeatureProcessor)
                .writer(pairFeatureWriter)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<Long> activeMemberReader(
            DataSource dataSource,
            FeatureComputationProperties properties,
            @Value("#{jobParameters['featureAsOf']}") String featureAsOfParameter) {
        Instant featureAsOf = JobParameterInstants.required(featureAsOfParameter, FEATURE_AS_OF);
        return new JdbcCursorItemReaderBuilder<Long>()
                .name("activeMemberReader")
                .dataSource(dataSource)
                .sql("SELECT id FROM members WHERE created_at <= ? ORDER BY id")
                .preparedStatementSetter(statement ->
                        statement.setTimestamp(1, Timestamp.from(featureAsOf)))
                .rowMapper((resultSet, rowNumber) -> resultSet.getLong("id"))
                .fetchSize(properties.getFetchSize())
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    PairFeatureItemProcessor pairFeatureProcessor(
            SnapshotCandidateRepository candidates,
            PairFeatureComputer computer,
            Clock batchClock,
            @Value("#{jobParameters['featureAsOf']}") String featureAsOfParameter) {
        Instant featureAsOf = JobParameterInstants.required(featureAsOfParameter, FEATURE_AS_OF);
        Instant computedAt = batchClock.instant();
        return new PairFeatureItemProcessor(candidates, computer, featureAsOf, computedAt);
    }

    @Bean
    ItemWriter<List<PymkPairFeature>> pairFeatureWriter(JdbcTemplate jdbc) {
        return chunk -> {
            List<PymkPairFeature> rows = chunk.getItems().stream()
                    .flatMap(List::stream)
                    .toList();
            if (!rows.isEmpty()) {
                jdbc.batchUpdate(UPSERT_FEATURE_SQL, rows, rows.size(),
                        FeatureComputationJobConfig::setFeatureParameters);
            }
        };
    }

    @Bean
    Step deleteStaleFeatureSnapshotsStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            org.springframework.batch.core.step.tasklet.Tasklet deleteStaleFeatureSnapshotsTasklet) {
        return new StepBuilder("deleteStaleFeatureSnapshotsStep", jobRepository)
                .tasklet(deleteStaleFeatureSnapshotsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    org.springframework.batch.core.step.tasklet.Tasklet deleteStaleFeatureSnapshotsTasklet(
            PymkPairFeatureRepository features,
            @Value("#{jobParameters['featureAsOf']}") String featureAsOfParameter) {
        Instant featureAsOf = JobParameterInstants.required(featureAsOfParameter, FEATURE_AS_OF);
        return (contribution, chunkContext) -> {
            features.deleteSnapshotsOlderThan(featureAsOf);
            return RepeatStatus.FINISHED;
        };
    }

    private static Instant parse(JobParameters parameters, String name)
            throws JobParametersInvalidException {
        try {
            return JobParameterInstants.required(parameters.getString(name), name);
        } catch (IllegalArgumentException exception) {
            throw new JobParametersInvalidException(exception.getMessage());
        }
    }

    static void validateMonotonicSnapshot(Instant requested, Optional<Instant> latest) {
        latest.ifPresent(materialized -> {
            if (requested.isBefore(materialized)) {
                throw new IllegalStateException(
                        "featureAsOf " + requested
                                + " is older than materialized snapshot " + materialized);
            }
        });
    }

    private static final String UPSERT_FEATURE_SQL = """
            INSERT INTO pymk_features (
                member_id, candidate_id, mutual_connection_count,
                same_company, same_school, same_geo, embedding_cosine_sim,
                profile_view_count_last_30d, feature_as_of, computed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (member_id, candidate_id) DO UPDATE SET
                mutual_connection_count = EXCLUDED.mutual_connection_count,
                same_company = EXCLUDED.same_company,
                same_school = EXCLUDED.same_school,
                same_geo = EXCLUDED.same_geo,
                embedding_cosine_sim = EXCLUDED.embedding_cosine_sim,
                profile_view_count_last_30d = EXCLUDED.profile_view_count_last_30d,
                feature_as_of = EXCLUDED.feature_as_of,
                computed_at = EXCLUDED.computed_at
            WHERE pymk_features.feature_as_of <= EXCLUDED.feature_as_of
            """;

    private static void setFeatureParameters(
            PreparedStatement statement, PymkPairFeature feature) throws java.sql.SQLException {
        statement.setLong(1, feature.getId().getMemberId());
        statement.setLong(2, feature.getId().getCandidateId());
        statement.setInt(3, feature.getMutualConnectionCount());
        statement.setBoolean(4, feature.isSameCompany());
        statement.setBoolean(5, feature.isSameSchool());
        statement.setBoolean(6, feature.isSameGeo());
        statement.setDouble(7, feature.getEmbeddingCosineSim());
        statement.setInt(8, feature.getProfileViewCountLast30d());
        statement.setTimestamp(9, Timestamp.from(feature.getFeatureAsOf()));
        statement.setTimestamp(10, Timestamp.from(feature.getComputedAt()));
    }

    public static class PairFeatureItemProcessor
            implements ItemProcessor<Long, List<PymkPairFeature>> {

        private final SnapshotCandidateRepository candidates;
        private final PairFeatureComputer computer;
        private final Instant featureAsOf;
        private final Instant computedAt;

        public PairFeatureItemProcessor(
                SnapshotCandidateRepository candidates,
                PairFeatureComputer computer,
                Instant featureAsOf,
                Instant computedAt) {
            this.candidates = candidates;
            this.computer = computer;
            this.featureAsOf = featureAsOf;
            this.computedAt = computedAt;
        }

        @Override
        public List<PymkPairFeature> process(Long memberId) {
            return candidates.findCandidates(memberId, featureAsOf).stream()
                    .map(candidateId -> computer.compute(
                            new PairId(memberId, candidateId), featureAsOf, computedAt))
                    .toList();
        }
    }
}
