package com.ahdyahmed.pymk.batch;

import java.nio.file.Path;
import java.time.Instant;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit local operator actions; staging never implicitly promotes a generation. */
@Configuration
public class ServingReleaseJobConfig {
    @Bean
    Job servingReleaseJob(JobRepository repository, Step servingReleaseActionStep) {
        return new JobBuilder("servingReleaseJob", repository).validator(p -> {
            if (p.getString("releaseId") == null || !java.util.Set.of("export", "activate", "rollback").contains(p.getString("action", ""))) {
                throw new JobParametersInvalidException("releaseId and action=export|activate|rollback are required");
            }
        }).start(servingReleaseActionStep).build();
    }

    @Bean
    Step servingReleaseActionStep(JobRepository repository, PlatformTransactionManager tx, Tasklet servingReleaseTasklet) {
        return new StepBuilder("servingReleaseActionStep", repository).tasklet(servingReleaseTasklet, tx).build();
    }

    @Bean @StepScope
    Tasklet servingReleaseTasklet(ServingReleaseService service,
            @Value("#{jobParameters['releaseId']}") String id,
            @Value("#{jobParameters['action']}") String action,
            @Value("#{jobParameters['expectedEpoch']}") String epoch,
            @Value("#{jobParameters['modelPath']}") String model,
            @Value("#{jobParameters['metadataPath']}") String metadata,
            @Value("#{jobParameters['legacyModelPath']}") String legacyModel,
            @Value("#{jobParameters['legacyMetadataPath']}") String legacyMetadata,
            @Value("#{jobParameters['labelWindowEnd']}") String labelEnd,
            @Value("#{jobParameters['outputPath']}") String output) {
        return (contribution, context) -> {
            switch (action) {
                case "export" -> service.exportTraining(id, Instant.parse(labelEnd), Path.of(output));
                case "activate" -> service.activate(id, Long.parseLong(epoch), Path.of(model), Path.of(metadata),
                        Path.of(legacyModel == null ? "artifacts/models/light-ranker/light-logreg-v1.onnx" : legacyModel),
                        Path.of(legacyMetadata == null ? "artifacts/models/light-ranker/light-logreg-v1.metadata.json" : legacyMetadata));
                case "rollback" -> service.rollback(id, Long.parseLong(epoch));
                default -> throw new IllegalArgumentException("Unknown action");
            }
            return RepeatStatus.FINISHED;
        };
    }
}
