package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahdyahmed.pymk.domain.entity.EventType;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.entity.MemberEvent;
import com.ahdyahmed.pymk.domain.repository.MemberEventRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** End-to-end Day 18 CSV export against migrated PostgreSQL. */
@SpringBatchTest
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "pymk.batch.training-export.chunk-size=2",
        "pymk.batch.training-export.fetch-size=2",
        "pymk.batch.training-export.observation-window=30d"
})
@Import(PostgresTestConfig.class)
class TrainingDatasetExportJobTest {

    private static final Instant FEATURE_AS_OF = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant LABEL_WINDOW_END = Instant.parse("2026-09-30T12:10:00Z");

    @TempDir Path temporaryDirectory;
    @Autowired JobLauncherTestUtils jobs;
    @Autowired @Qualifier("trainingDatasetExportJob") Job trainingDatasetExportJob;
    @Autowired @Qualifier("trainingDatasetExportJobParametersValidator")
    JobParametersValidator validator;
    @Autowired MemberRepository members;
    @Autowired MemberEventRepository events;
    @Autowired PymkPairFeatureRepository features;

    @BeforeEach
    void seedSnapshot() {
        jobs.setJob(trainingDatasetExportJob);
        features.deleteAllInBatch();
        events.deleteAllInBatch();
        members.deleteAllInBatch();

        Instant createdAt = FEATURE_AS_OF.minus(365, ChronoUnit.DAYS);
        members.saveAllAndFlush(List.of(
                member(1L, createdAt),
                member(2L, createdAt),
                member(3L, createdAt),
                member(4L, createdAt)));
        features.saveAllAndFlush(List.of(
                feature(1L, 2L, 2, 0.75),
                feature(1L, 3L, 1, 0.25),
                feature(2L, 4L, 0, -0.5)));

        events.saveAllAndFlush(List.of(
                // Strictly inside the label window: the only positive row.
                new MemberEvent(1L, 2L, EventType.INVITE_ACCEPTED,
                        FEATURE_AS_OF.plusSeconds(60)),
                // At the snapshot belongs to observation history, not the label window.
                new MemberEvent(1L, 3L, EventType.INVITE_ACCEPTED, FEATURE_AS_OF),
                // After the mature window must not turn a negative into a positive.
                new MemberEvent(2L, 4L, EventType.INVITE_ACCEPTED,
                        LABEL_WINDOW_END.plusSeconds(1)),
                // Direction and outcome type are both intentional label dimensions.
                new MemberEvent(3L, 1L, EventType.INVITE_ACCEPTED,
                        FEATURE_AS_OF.plusSeconds(120)),
                new MemberEvent(2L, 4L, EventType.INVITE_SENT,
                        FEATURE_AS_OF.plusSeconds(120))));
    }

    @Test
    void exportsOrderedLeakageSafeLabelsAndAtomicallyReplacesOnRerun() throws Exception {
        Path output = temporaryDirectory.resolve("nested").resolve("training.csv");

        JobExecution firstRun = jobs.launchJob(parameters(output, 1));

        assertThat(firstRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(firstRun.getStepExecutions())
                .extracting(execution -> execution.getStepName())
                .containsExactlyInAnyOrder(
                        "validateTrainingExportStep",
                        "writeTrainingDatasetStep",
                        "promoteTrainingDatasetStep");
        List<String> lines = Files.readAllLines(output);
        assertThat(lines).containsExactly(
                TrainingExample.CSV_HEADER,
                "1,2,2,true,false,true,0.75000000000000000,3,"
                        + "2026-08-31T12:00:00Z,2026-09-30T12:00:00Z,"
                        + "2026-09-30T12:10:00Z,1",
                "1,3,1,true,false,true,0.25000000000000000,3,"
                        + "2026-08-31T12:00:00Z,2026-09-30T12:00:00Z,"
                        + "2026-09-30T12:10:00Z,0",
                "2,4,0,true,false,true,-0.50000000000000000,3,"
                        + "2026-08-31T12:00:00Z,2026-09-30T12:00:00Z,"
                        + "2026-09-30T12:10:00Z,0");
        assertThat(temporaryDirectory.resolve("nested").toFile().list())
                .containsExactly("training.csv");

        JobExecution rerun = jobs.launchJob(parameters(output, 2));
        assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(Files.readAllLines(output)).isEqualTo(lines);
    }

    @Test
    void rejectsMissingReversedAndImmatureWindows() {
        assertThatThrownBy(() -> validator.validate(new JobParameters()))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining(TrainingDatasetExportJobConfig.FEATURE_AS_OF);

        Path output = temporaryDirectory.resolve("training.csv");
        JobParameters reversed = new JobParametersBuilder()
                .addString(TrainingDatasetExportJobConfig.FEATURE_AS_OF,
                        LABEL_WINDOW_END.toString())
                .addString(TrainingDatasetExportJobConfig.LABEL_WINDOW_END,
                        FEATURE_AS_OF.toString())
                .addString(TrainingDatasetExportJobConfig.OUTPUT_PATH, output.toString())
                .toJobParameters();
        assertThatThrownBy(() -> validator.validate(reversed))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("must be before");

        JobParameters immature = new JobParametersBuilder()
                .addString(TrainingDatasetExportJobConfig.FEATURE_AS_OF,
                        FEATURE_AS_OF.toString())
                .addString(TrainingDatasetExportJobConfig.LABEL_WINDOW_END,
                        LABEL_WINDOW_END.plusSeconds(1).toString())
                .addString(TrainingDatasetExportJobConfig.OUTPUT_PATH, output.toString())
                .toJobParameters();
        assertThatThrownBy(() -> validator.validate(immature))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("incomplete windows bias negatives");
    }

    private JobParameters parameters(Path output, long runId) {
        return new JobParametersBuilder()
                .addString(TrainingDatasetExportJobConfig.FEATURE_AS_OF,
                        FEATURE_AS_OF.toString())
                .addString(TrainingDatasetExportJobConfig.LABEL_WINDOW_END,
                        LABEL_WINDOW_END.toString())
                .addString(TrainingDatasetExportJobConfig.OUTPUT_PATH, output.toString())
                .addLong("run.id", runId)
                .toJobParameters();
    }

    private static Member member(long id, Instant createdAt) {
        return new Member(id, "Member " + id, "Engineer", "Acme", "School", "Cairo", createdAt);
    }

    private static PymkPairFeature feature(
            long memberId, long candidateId, int mutualCount, double cosine) {
        return new PymkPairFeature(
                new PairId(memberId, candidateId),
                mutualCount,
                true,
                false,
                true,
                cosine,
                3,
                FEATURE_AS_OF,
                FEATURE_AS_OF.plusSeconds(1));
    }
}
