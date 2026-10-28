package com.ahdyahmed.pymk.api.events;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "pymk.events.relay.enabled", havingValue = "true")
public class OutboxRelayConfiguration {
    @Bean
    DefaultKafkaProducerFactory<String, String> outboxProducerFactory(
            @Value("${pymk.events.relay.bootstrap-servers:localhost:9092}") String bootstrap) {
        var factory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000));
        factory.setPhysicalCloseTimeout(5);
        return factory;
    }

    @Bean
    KafkaTemplate<String, String> outboxKafkaTemplate(DefaultKafkaProducerFactory<String, String> outboxProducerFactory) {
        var template = new KafkaTemplate<>(outboxProducerFactory);
        var listener = new org.springframework.kafka.support.LoggingProducerListener<String, String>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        return template;
    }

    @Bean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager manager,
            DefaultKafkaProducerFactory<String, String> outboxProducerFactory,
            KafkaTemplate<String, String> outboxKafkaTemplate,
            @Value("${pymk.events.relay.batch-size:50}") int batchSize,
            @Value("${pymk.events.relay.lease-seconds:60}") int leaseSeconds,
            @Value("${pymk.events.relay.retry-seconds:5}") int retrySeconds) {
        return new OutboxRelay(jdbc, manager, message -> {
            try {
                outboxKafkaTemplate.send(message.topic(), message.key(), message.json()).get(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outboxProducerFactory.reset();
                throw new IllegalStateException("Outbox publisher interrupted", interrupted);
            } catch (Exception failure) {
                // Discard the local producer before releasing the row lock after an uncertain send.
                outboxProducerFactory.reset();
                throw new IllegalStateException("Outbox acknowledgement failed", failure);
            }
        }, batchSize, leaseSeconds, retrySeconds);
    }

    @Bean
    RelaySchedule relaySchedule(OutboxRelay relay) { return new RelaySchedule(relay); }

    static class RelaySchedule {
        private final OutboxRelay relay;
        RelaySchedule(OutboxRelay relay) { this.relay = relay; }
        @Scheduled(fixedDelayString = "${pymk.events.relay.poll-ms:1000}")
        public void poll() { relay.tick(); }
    }
}
