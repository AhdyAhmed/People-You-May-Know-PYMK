package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OutboxRelayConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(OutboxRelayConfiguration.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> {
                var manager = mock(PlatformTransactionManager.class);
                when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
                return manager;
            });

    @Test void relayIsOptIn() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxRelay.class));
    }

    @Test void enabledConfigurationCoexistsWithBootAndSchedulesRelay() {
        runner.withPropertyValues("pymk.events.relay.enabled=true", "pymk.events.relay.poll-ms=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OutboxRelay.class).hasSingleBean(KafkaTemplate.class);
                    assertThat(context).hasSingleBean(OutboxRelayConfiguration.RelaySchedule.class);
                });
    }
}
