package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.KafkaTopics;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pymk.events.consumers.enabled", havingValue = "true")
public class EventConsumerConfiguration {
    @Bean
    DefaultKafkaProducerFactory<byte[], byte[]> deadLetterProducerFactory(
            @Value("${pymk.events.consumers.bootstrap-servers:localhost:9092}") String bootstrap) {
        var settings = new java.util.HashMap<String, Object>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000));
        settings.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2097152);
        var factory = new DefaultKafkaProducerFactory<byte[], byte[]>(settings);
        factory.setPhysicalCloseTimeout(5);
        return factory;
    }

    @Bean
    KafkaTemplate<byte[], byte[]> deadLetterKafkaTemplate(DefaultKafkaProducerFactory<byte[], byte[]> deadLetterProducerFactory) {
        var template = new KafkaTemplate<>(deadLetterProducerFactory);
        var listener = new org.springframework.kafka.support.LoggingProducerListener<byte[], byte[]>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        return template;
    }

    @Bean
    EventDeadLetterPublisher eventDeadLetterPublisher(KafkaTemplate<byte[], byte[]> deadLetterKafkaTemplate) {
        return new EventDeadLetterPublisher(deadLetterKafkaTemplate);
    }

    @Bean
    ConcurrentMessageListenerContainer<byte[], byte[]> featureEventConsumer(DurableEventConsumer consumer,
            EventDeadLetterPublisher recoverer,
            @Value("${pymk.events.consumers.bootstrap-servers:localhost:9092}") String bootstrap) {
        return container(KafkaTopics.FEATURE_PROJECTION_GROUP, bootstrap, consumer, recoverer);
    }

    @Bean
    ConcurrentMessageListenerContainer<byte[], byte[]> cacheEventConsumer(DurableEventConsumer consumer,
            EventDeadLetterPublisher recoverer,
            @Value("${pymk.events.consumers.bootstrap-servers:localhost:9092}") String bootstrap) {
        return container(KafkaTopics.CACHE_INVALIDATION_GROUP, bootstrap, consumer, recoverer);
    }

    public static ConcurrentMessageListenerContainer<byte[], byte[]> container(String group, String bootstrap,
            DurableEventConsumer consumer, EventDeadLetterPublisher recoverer) {
        var factory = new DefaultKafkaConsumerFactory<byte[], byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000));
        var properties = new ContainerProperties(KafkaTopics.MEMBER_EVENTS, KafkaTopics.CONNECTION_EVENTS);
        properties.setGroupId(group);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setSyncCommits(true);
        properties.setPollTimeout(500);
        properties.setMessageListener((MessageListener<byte[], byte[]>) record -> consumer.accept(group, record));
        var container = new ConcurrentMessageListenerContainer<>(factory, properties);
        container.setBeanName(group);
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(250);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2000);
        var handler = new DefaultErrorHandler((record, failure) -> {
            @SuppressWarnings("unchecked")
            var typed = (org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>) record;
            recoverer.recover(group, typed, failure);
        }, backoff);
        // Including malformed records in bounded backoff also paces repeated DLT recovery failures.
        handler.setClassifications(Map.of(Exception.class, true), true);
        handler.setResetStateOnRecoveryFailure(true);
        handler.setResetStateOnExceptionChange(false);
        container.setCommonErrorHandler(handler);
        return container;
    }
}
