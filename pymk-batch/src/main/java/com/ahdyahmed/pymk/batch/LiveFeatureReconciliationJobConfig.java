package com.ahdyahmed.pymk.batch;

import com.ahdyahmed.pymk.featurestore.LiveFeatureStore;
import java.time.Clock;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration
public class LiveFeatureReconciliationJobConfig {
    @Bean Job liveFeatureReconciliationJob(JobRepository repository, Step liveFeatureReconciliationStep, Clock clock) {
        return new JobBuilder("liveFeatureReconciliationJob", repository).validator(parameters -> {
            try {
                var start = JobParameterInstants.required(parameters.getString("windowStart"), "windowStart");
                var end = JobParameterInstants.required(parameters.getString("windowEnd"), "windowEnd");
                var cutoff = JobParameterInstants.required(parameters.getString("knowledgeCutoff"), "knowledgeCutoff");
                if (!start.isBefore(end) || end.isAfter(clock.instant()) || cutoff.isAfter(clock.instant()))
                    throw new IllegalArgumentException("Window must increase; end and knowledge cutoff cannot be future");
            } catch (IllegalArgumentException invalid) { throw new JobParametersInvalidException(invalid.getMessage()); }
        }).start(liveFeatureReconciliationStep).build();
    }

    @Bean Step liveFeatureReconciliationStep(JobRepository repository, PlatformTransactionManager manager,
                                             Tasklet liveFeatureReconciliationTasklet) {
        var transaction = new DefaultTransactionAttribute();
        transaction.setTimeout(120);
        return new StepBuilder("liveFeatureReconciliationStep", repository)
                .tasklet(liveFeatureReconciliationTasklet, manager).transactionAttribute(transaction).build();
    }

    @Bean @StepScope Tasklet liveFeatureReconciliationTasklet(LiveFeatureStore store,
            @Value("#{jobParameters['windowStart']}") String start,
            @Value("#{jobParameters['windowEnd']}") String end,
            @Value("#{jobParameters['knowledgeCutoff']}") String cutoff) {
        return (contribution, context) -> {
            long generation = store.reconcile(JobParameterInstants.required(start, "windowStart"),
                    JobParameterInstants.required(end, "windowEnd"),
                    JobParameterInstants.required(cutoff, "knowledgeCutoff"));
            context.getStepContext().getStepExecution().getExecutionContext().putLong("liveGeneration", generation);
            return RepeatStatus.FINISHED;
        };
    }
}
