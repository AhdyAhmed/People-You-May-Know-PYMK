package com.ahdyahmed.pymk.batch;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Day 18 job: export snapshot features with future-window acceptance labels. */
@Configuration
public class TrainingDatasetExportJobConfig {

    public static final String JOB_NAME = "trainingDatasetExportJob";
    public static final String FEATURE_AS_OF = "featureAsOf";
    public static final String LABEL_WINDOW_END = "labelWindowEnd";
    public static final String OUTPUT_PATH = "outputPath";

    @Bean
    Job trainingDatasetExportJob(
            JobRepository jobRepository,
            JobParametersValidator trainingDatasetExportJobParametersValidator,
            Step validateTrainingExportStep,
            Step writeTrainingDatasetStep,
            Step promoteTrainingDatasetStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(trainingDatasetExportJobParametersValidator)
                .start(validateTrainingExportStep)
                .next(writeTrainingDatasetStep)
                .next(promoteTrainingDatasetStep)
                .build();
    }

    @Bean
    JobParametersValidator trainingDatasetExportJobParametersValidator(Clock batchClock) {
        return parameters -> {
            Instant featureAsOf = parseInstant(parameters, FEATURE_AS_OF);
            Instant labelWindowEnd = parseInstant(parameters, LABEL_WINDOW_END);
            if (!featureAsOf.isBefore(labelWindowEnd)) {
                throw new JobParametersInvalidException(
                        "featureAsOf must be before labelWindowEnd");
            }
            if (labelWindowEnd.isAfter(batchClock.instant())) {
                throw new JobParametersInvalidException(
                        "labelWindowEnd must not be in the future; incomplete windows bias negatives");
            }
            try {
                TrainingDatasetPaths.output(parameters.getString(OUTPUT_PATH));
            } catch (IllegalArgumentException exception) {
                throw new JobParametersInvalidException(exception.getMessage());
            }
        };
    }

    @Bean
    Step validateTrainingExportStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            Tasklet validateTrainingExportTasklet) {
        return new StepBuilder("validateTrainingExportStep", jobRepository)
                .tasklet(validateTrainingExportTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    Tasklet validateTrainingExportTasklet(
            JdbcTemplate jdbc,
            @Value("#{jobParameters['featureAsOf']}") String featureAsOfParameter,
            @Value("#{jobParameters['outputPath']}") String outputPath,
            @Value("#{stepExecution.jobExecution.id}") Long executionId) {
        Instant featureAsOf = JobParameterInstants.required(featureAsOfParameter, FEATURE_AS_OF);
        return (contribution, chunkContext) -> {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pymk_features WHERE feature_as_of = ?",
                    Long.class,
                    Timestamp.from(featureAsOf));
            if (count == null || count == 0) {
                throw new IllegalStateException(
                        "No feature rows exist for featureAsOf " + featureAsOf);
            }
            Path output = TrainingDatasetPaths.output(outputPath);
            if (Files.isDirectory(output)) {
                throw new IllegalStateException("outputPath must name a file, not a directory");
            }
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(TrainingDatasetPaths.staging(outputPath, executionId));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step writeTrainingDatasetStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<TrainingExample> trainingExampleReader,
            FlatFileItemWriter<TrainingExample> trainingExampleWriter,
            TrainingDatasetExportProperties properties) {
        properties.validate();
        return new StepBuilder("writeTrainingDatasetStep", jobRepository)
                .<TrainingExample, TrainingExample>chunk(properties.getChunkSize(), transactionManager)
                .reader(trainingExampleReader)
                .writer(trainingExampleWriter)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<TrainingExample> trainingExampleReader(
            DataSource dataSource,
            TrainingDatasetExportProperties properties,
            @Value("#{jobParameters['featureAsOf']}") String featureAsOfParameter,
            @Value("#{jobParameters['labelWindowEnd']}") String labelWindowEndParameter) {
        Instant featureAsOf = JobParameterInstants.required(featureAsOfParameter, FEATURE_AS_OF);
        Instant labelWindowEnd = JobParameterInstants.required(
                labelWindowEndParameter, LABEL_WINDOW_END);
        Instant observationWindowStart = featureAsOf.minus(properties.getObservationWindow());
        return new JdbcCursorItemReaderBuilder<TrainingExample>()
                .name("trainingExampleReader")
                .dataSource(dataSource)
                .sql(TRAINING_EXAMPLE_SQL)
                .preparedStatementSetter(statement -> {
                    statement.setTimestamp(1, Timestamp.from(labelWindowEnd));
                    statement.setTimestamp(2, Timestamp.from(featureAsOf));
                })
                .rowMapper((resultSet, rowNumber) -> new TrainingExample(
                        resultSet.getLong("member_id"),
                        resultSet.getLong("candidate_id"),
                        resultSet.getInt("mutual_connection_count"),
                        resultSet.getBoolean("same_company"),
                        resultSet.getBoolean("same_school"),
                        resultSet.getBoolean("same_geo"),
                        resultSet.getDouble("embedding_cosine_sim"),
                        resultSet.getInt("profile_view_count_last_30d"),
                        observationWindowStart,
                        featureAsOf,
                        labelWindowEnd,
                        resultSet.getInt("label")))
                .fetchSize(properties.getFetchSize())
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemWriter<TrainingExample> trainingExampleWriter(
            @Value("#{jobParameters['outputPath']}") String outputPath,
            @Value("#{stepExecution.jobExecution.id}") Long executionId) {
        Path staging = TrainingDatasetPaths.staging(outputPath, executionId);
        return new FlatFileItemWriterBuilder<TrainingExample>()
                .name("trainingExampleWriter")
                .resource(new FileSystemResource(staging))
                .headerCallback(writer -> writer.write(TrainingExample.CSV_HEADER))
                .lineAggregator(TrainingExample::toCsv)
                .shouldDeleteIfExists(true)
                .saveState(true)
                .build();
    }

    @Bean
    Step promoteTrainingDatasetStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            Tasklet promoteTrainingDatasetTasklet) {
        return new StepBuilder("promoteTrainingDatasetStep", jobRepository)
                .tasklet(promoteTrainingDatasetTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    Tasklet promoteTrainingDatasetTasklet(
            @Value("#{jobParameters['outputPath']}") String outputPath,
            @Value("#{stepExecution.jobExecution.id}") Long executionId) {
        return (contribution, chunkContext) -> {
            Path staging = TrainingDatasetPaths.staging(outputPath, executionId);
            Path output = TrainingDatasetPaths.output(outputPath);
            promote(staging, output);
            return RepeatStatus.FINISHED;
        };
    }

    static void promote(Path staging, Path output) throws IOException {
        try {
            Files.move(staging, output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Instant parseInstant(JobParameters parameters, String name)
            throws JobParametersInvalidException {
        try {
            return JobParameterInstants.required(parameters.getString(name), name);
        } catch (IllegalArgumentException exception) {
            throw new JobParametersInvalidException(exception.getMessage());
        }
    }

    private static final String TRAINING_EXAMPLE_SQL = """
            SELECT f.member_id,
                   f.candidate_id,
                   f.mutual_connection_count,
                   f.same_company,
                   f.same_school,
                   f.same_geo,
                   f.embedding_cosine_sim,
                   f.profile_view_count_last_30d,
                   CASE WHEN EXISTS (
                       SELECT 1
                       FROM member_events event
                       WHERE event.actor_member_id = f.member_id
                         AND event.target_member_id = f.candidate_id
                         AND event.type = 'INVITE_ACCEPTED'
                         AND event.occurred_at > f.feature_as_of
                         AND event.occurred_at <= ?
                   ) THEN 1 ELSE 0 END AS label
            FROM pymk_features f
            WHERE f.feature_as_of = ?
            ORDER BY f.feature_as_of, f.member_id, f.candidate_id
            """;
}
