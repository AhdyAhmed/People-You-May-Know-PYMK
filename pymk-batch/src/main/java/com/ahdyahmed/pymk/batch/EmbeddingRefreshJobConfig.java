package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import java.time.Instant;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

/** Import frozen Day 22 vectors, then compute compatible features in resumable member chunks. */
@Configuration
public class EmbeddingRefreshJobConfig {
    static final String HEADER = "member_id,embedding,updated_at,embedding_version,fit_cutoff";

    @Bean
    Job embeddingRefreshJob(JobRepository repository, Step prepareEmbeddingReleaseStep,
            Step importEmbeddingVectorsStep, Step validateEmbeddingCoverageStep, Step computeReleaseFeaturesStep) {
        return new JobBuilder("embeddingRefreshJob", repository)
                .validator(p -> {
                    if (p.getString("manifestPath") == null) throw new org.springframework.batch.core.JobParametersInvalidException("manifestPath is required");
                })
                .start(prepareEmbeddingReleaseStep).next(importEmbeddingVectorsStep)
                .next(validateEmbeddingCoverageStep).next(computeReleaseFeaturesStep).build();
    }

    @Bean
    Step prepareEmbeddingReleaseStep(JobRepository repository, PlatformTransactionManager tx, Tasklet prepareEmbeddingReleaseTasklet) {
        return new StepBuilder("prepareEmbeddingReleaseStep", repository).allowStartIfComplete(true)
                .tasklet(prepareEmbeddingReleaseTasklet, tx).build();
    }

    @Bean @StepScope
    Tasklet prepareEmbeddingReleaseTasklet(EmbeddingReleaseService service,
            @Value("#{jobParameters['manifestPath']}") String path) {
        return (contribution, context) -> { service.prepare(EmbeddingReleaseManifest.read(path)); return RepeatStatus.FINISHED; };
    }

    @Bean
    Step importEmbeddingVectorsStep(JobRepository repository, PlatformTransactionManager tx,
            FlatFileItemReader<MemberEmbedding> releaseEmbeddingReader, ItemWriter<MemberEmbedding> releaseEmbeddingWriter) {
        return new StepBuilder("importEmbeddingVectorsStep", repository)
                .<MemberEmbedding, MemberEmbedding>chunk(500, tx)
                .reader(releaseEmbeddingReader).writer(releaseEmbeddingWriter).build();
    }

    @Bean @StepScope
    FlatFileItemReader<MemberEmbedding> releaseEmbeddingReader(EmbeddingReleaseService service,
            @Value("#{jobParameters['manifestPath']}") String path) {
        var manifest = EmbeddingReleaseManifest.read(path);
        boolean needed = service.needsImport(manifest.id());
        return new FlatFileItemReaderBuilder<MemberEmbedding>()
                .name("releaseEmbeddingReader").resource(needed ? new FileSystemResource(manifest.vectors())
                        : new org.springframework.core.io.ByteArrayResource((HEADER + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .linesToSkip(1)
                .skippedLinesCallback(line -> { if (!HEADER.equals(line)) throw new IllegalArgumentException("Unexpected embedding CSV header"); })
                .delimited().names(HEADER.split(","))
                .fieldSetMapper(fields -> {
                    try { return new MemberEmbedding(fields.readLong("member_id"),
                            EmbeddingReleaseManifest.JSON.readValue(fields.readString("embedding"), float[].class),
                            Instant.parse(fields.readString("updated_at")), fields.readString("embedding_version"),
                            Instant.parse(fields.readString("fit_cutoff"))); }
                    catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid vector JSON", ex); }
                }).saveState(true).build();
    }

    @Bean @StepScope
    ItemWriter<MemberEmbedding> releaseEmbeddingWriter(EmbeddingReleaseService service,
            @Value("#{jobParameters['manifestPath']}") String path) {
        var manifest = EmbeddingReleaseManifest.read(path);
        return chunk -> { for (var vector : chunk) service.insertVector(manifest, vector); };
    }

    @Bean
    Step validateEmbeddingCoverageStep(JobRepository repository, PlatformTransactionManager tx, Tasklet validateEmbeddingCoverageTasklet) {
        return new StepBuilder("validateEmbeddingCoverageStep", repository).tasklet(validateEmbeddingCoverageTasklet, tx).build();
    }

    @Bean @StepScope
    Tasklet validateEmbeddingCoverageTasklet(EmbeddingReleaseService service,
            @Value("#{jobParameters['manifestPath']}") String path) {
        return (contribution, context) -> { service.finishImport(EmbeddingReleaseManifest.read(path)); return RepeatStatus.FINISHED; };
    }

    @Bean
    Step computeReleaseFeaturesStep(JobRepository repository, PlatformTransactionManager tx, Tasklet computeReleaseFeaturesTasklet) {
        return new StepBuilder("computeReleaseFeaturesStep", repository).tasklet(computeReleaseFeaturesTasklet, tx).build();
    }

    @Bean @StepScope
    Tasklet computeReleaseFeaturesTasklet(EmbeddingReleaseService service,
            @Value("#{jobParameters['manifestPath']}") String path) {
        var manifest = EmbeddingReleaseManifest.read(path);
        return (contribution, context) -> service.computeNextChunk(manifest) ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
    }
}
