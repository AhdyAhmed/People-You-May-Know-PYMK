package com.ahdyahmed.pymk.stream.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class KafkaContractIntegrationTest {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withListener("localhost:19092")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private final EventContract contract = new EventContract();

    @BeforeAll
    static void initializeRealTopicsUsingTheComposeScript() throws Exception {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        KAFKA.copyFileToContainer(MountableFile.forHostPath(root.resolve("infra/kafka/create-topics.sh")), "/tmp/pymk-create-topics.sh");
        initialize();
        initialize(); // repeated startup must neither lose data nor silently alter topology
    }

    private static void initialize() throws Exception {
        var result = KAFKA.execInContainer("/bin/bash", "/tmp/pymk-create-topics.sh", "localhost:19092");
        assertThat(result.getExitCode()).withFailMessage(result.getStdout() + result.getStderr()).isZero();
    }

    @Test
    void developmentTopologyMatchesJavaContractAndConfigurationDriftFailsClosed() throws Exception {
        try (Admin admin = admin()) {
            for (var expected : KafkaTopics.developmentTopics()) {
                var topic = admin.describeTopics(List.of(expected.name())).allTopicNames().get(20, TimeUnit.SECONDS).get(expected.name());
                assertThat(topic.partitions()).hasSize(expected.numPartitions());
                assertThat(topic.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
                var resource = new ConfigResource(ConfigResource.Type.TOPIC, expected.name());
                var actual = admin.describeConfigs(List.of(resource)).all().get(20, TimeUnit.SECONDS).get(resource);
                expected.configs().forEach((key, value) -> assertThat(actual.get(key).value()).as(key).isEqualTo(value));
            }
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, KafkaTopics.MEMBER_EVENTS);
            try {
                changeRetention(admin, resource, "86400000");
                var rejected = KAFKA.execInContainer("/bin/bash", "/tmp/pymk-create-topics.sh", "localhost:19092");
                assertThat(rejected.getExitCode()).isNotZero();
                assertThat(rejected.getStderr()).contains("Unexpected topic configuration");
            } finally {
                changeRetention(admin, resource, Long.toString(KafkaTopics.RETENTION_MS));
            }
        }
    }

    @Test
    void springKafkaPublishesValidatedRecordsAndIndependentGroupsReceiveOrderedReplay() throws Exception {
        var member = contract.decode(KafkaTopics.MEMBER_EVENTS, "42", EventContractTest.fixture("member-event.json"));
        var connection = contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", EventContractTest.fixture("connection-event.json"));
        var second = new EventEnvelope(1, UUID.randomUUID(), StreamEventType.INVITE_SENT, member.occurredAt(),
                member.ingestedAt(), 42, 100, member.correlationId(), Map.of());
        var factory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000));
        try {
            var template = new KafkaTemplate<>(factory);
            var firstResult = template.send(member.topic(), member.key(), contract.encode(member)).get(20, TimeUnit.SECONDS);
            var secondResult = template.send(second.topic(), second.key(), contract.encode(second)).get(20, TimeUnit.SECONDS);
            template.send(connection.topic(), connection.key(), contract.encode(connection)).get(20, TimeUnit.SECONDS);
            assertThat(secondResult.getRecordMetadata().partition()).isEqualTo(firstResult.getRecordMetadata().partition());
            assertThat(secondResult.getRecordMetadata().offset()).isGreaterThan(firstResult.getRecordMetadata().offset());
        } finally {
            factory.destroy();
        }
        String firstGroup = KafkaTopics.FEATURE_PROJECTION_GROUP + "-" + UUID.randomUUID();
        for (String group : List.of(firstGroup, KafkaTopics.CACHE_INVALIDATION_GROUP + "-" + UUID.randomUUID())) {
            try (var consumer = consumer(group)) {
                consumer.subscribe(List.of(member.topic(), connection.topic()));
                var records = receive(consumer, 3);
                assertThat(records).hasSize(3);
                assertThat(records.stream().filter(record -> record.topic().equals(member.topic()))
                        .map(record -> contract.decode(record.topic(), record.key(), record.value()).eventId()))
                        .containsExactly(member.eventId(), second.eventId());
                assertThat(records.stream().map(record -> contract.decode(record.topic(), record.key(), record.value())))
                        .containsExactlyInAnyOrder(member, second, connection);
                consumer.commitSync(Duration.ofSeconds(10));
            }
        }
        // A replacement consumer in an existing group resumes committed offsets rather than replaying.
        try (var resumed = consumer(firstGroup)) {
            resumed.subscribe(List.of(member.topic(), connection.topic()));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (resumed.assignment().isEmpty() && System.nanoTime() < deadline) {
                assertThat(resumed.poll(Duration.ofMillis(250))).isEmpty();
            }
            assertThat(resumed.assignment()).hasSize(6);
            assertThat(resumed.poll(Duration.ofSeconds(1))).isEmpty();
        }
        try (Admin admin = admin()) {
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS))
                    .containsAll(Set.of(KafkaTopics.MEMBER_EVENTS, KafkaTopics.CONNECTION_EVENTS));
        }
    }

    private static List<ConsumerRecord<String, String>> receive(KafkaConsumer<String, String> consumer, int count) {
        var result = new ArrayList<ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (result.size() < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(result::add);
        }
        return result;
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000));
    }

    private static Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "default.api.timeout.ms", "15000", "request.timeout.ms", "10000"));
    }

    private static void changeRetention(Admin admin, ConfigResource resource, String value) throws Exception {
        admin.incrementalAlterConfigs(Map.of(resource,
                List.of(new AlterConfigOp(new ConfigEntry("retention.ms", value), AlterConfigOp.OpType.SET))))
                .all().get(20, TimeUnit.SECONDS);
    }
}
