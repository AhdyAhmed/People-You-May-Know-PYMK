package com.ahdyahmed.pymk.stream.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** Explicitly selected by day31-kafka-smoke.ps1, not part of the normal *Test suite. */
class KafkaComposeSmoke {
    @Test
    void hostListenerTopologyAndDurableRecord() throws Exception {
        String bootstrap = System.getProperty("pymk.kafka.bootstrap");
        String topic = System.getProperty("pymk.kafka.smoke.topic");
        String phase = System.getProperty("pymk.kafka.smoke.phase");
        assertThat(bootstrap).isNotBlank();
        assertThat(topic).matches("pymk\\.smoke\\.[a-f0-9]{10}");
        assertThat(phase).isIn("write", "read");
        var contract = new EventContract();
        var expected = contract.decode(KafkaTopics.MEMBER_EVENTS, "42", EventContractTest.fixture("member-event.json"));
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap,
                "default.api.timeout.ms", "15000", "request.timeout.ms", "10000"))) {
            for (var definition : KafkaTopics.developmentTopics()) {
                var description = admin.describeTopics(List.of(definition.name())).allTopicNames().get(20, TimeUnit.SECONDS).get(definition.name());
                assertThat(description.partitions()).hasSize(definition.numPartitions());
                assertThat(description.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
                var resource = new ConfigResource(ConfigResource.Type.TOPIC, definition.name());
                var configs = admin.describeConfigs(List.of(resource)).all().get(20, TimeUnit.SECONDS).get(resource);
                definition.configs().forEach((key, value) -> assertThat(configs.get(key).value()).isEqualTo(value));
            }
            if (phase.equals("write")) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)
                        .configs(Map.of("retention.ms", "3600000")))).all().get(20, TimeUnit.SECONDS);
                try (var producer = new KafkaProducer<String, String>(Map.of(
                        "bootstrap.servers", bootstrap, "key.serializer", StringSerializer.class,
                        "value.serializer", StringSerializer.class, "acks", "all", "enable.idempotence", true,
                        "max.block.ms", 10000))) {
                    producer.send(new ProducerRecord<>(topic, 0, expected.key(), contract.encode(expected))).get(20, TimeUnit.SECONDS);
                }
            }
        }
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", bootstrap, "key.deserializer", StringDeserializer.class,
                "value.deserializer", StringDeserializer.class, "enable.auto.commit", false))) {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var records = new ArrayList<ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            assertThat(records).hasSize(1);
            assertThat(contract.decode(KafkaTopics.MEMBER_EVENTS, records.getFirst().key(), records.getFirst().value())).isEqualTo(expected);
        }
    }
}
