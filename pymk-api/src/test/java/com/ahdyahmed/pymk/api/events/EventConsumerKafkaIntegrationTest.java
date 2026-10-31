package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.ahdyahmed.pymk.stream.contract.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"pymk.events.consumers.enabled=true", "pymk.events.relay.enabled=true",
        "pymk.events.monitoring.enabled=true", "pymk.events.monitoring.poll-ms=3600000",
        "management.endpoints.web.exposure.include=health,info,metrics"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@MockitoBean(types = LightRankerModel.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class EventConsumerKafkaIntegrationTest {
    // Docker reassigns random published ports on stop/start; keep the advertised listener stable.
    static final int BROKER_PORT = availablePort();
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", BROKER_PORT),
                            new com.github.dockerjava.api.model.ExposedPort(9092))));
    static int availablePort() {
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static final String FEATURE = KafkaTopics.FEATURE_PROJECTION_GROUP;
    static final String CACHE = KafkaTopics.CACHE_INVALIDATION_GROUP;
    final EventContract contract = new EventContract();
    @Autowired JdbcTemplate jdbc;
    @Autowired DurableEventConsumer service;
    @Autowired MockMvc mvc;
    @Autowired EventMonitor monitor;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired @Qualifier("deadLetterKafkaTemplate") KafkaTemplate<byte[], byte[]> template;
    @Autowired @Qualifier("featureEventConsumer") ConcurrentMessageListenerContainer<byte[], byte[]> feature;
    @Autowired @Qualifier("cacheEventConsumer") ConcurrentMessageListenerContainer<byte[], byte[]> cache;
    @MockitoSpyBean EventDeadLetterPublisher recoverer;

    @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("pymk.events.consumers.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("pymk.events.relay.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @BeforeAll static void topics() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(KafkaTopics.developmentTopics()).all().get(20, TimeUnit.SECONDS);
        }
    }
    @AfterEach void restore() {
        reset(recoverer);
        if (!feature.isRunning()) feature.start();
    }

    long effects(String group, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM event_consumer_journal WHERE consumer_name = ? AND event_id = ?",
                Long.class, group, id);
    }
    void appliedBoth(UUID id) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(effects(FEATURE, id)).isEqualTo(1);
            assertThat(effects(CACHE, id)).isEqualTo(1);
        });
    }
    ProducerRecord<byte[], byte[]> wire(EventEnvelope event) {
        return new ProducerRecord<>(event.topic(), 0, event.key().getBytes(StandardCharsets.UTF_8),
                contract.encode(event).getBytes(StandardCharsets.UTF_8));
    }
    RecordMetadata send(EventEnvelope event) throws Exception {
        return template.send(wire(event)).get(20, TimeUnit.SECONDS).getRecordMetadata();
    }
    long committed(String group, RecordMetadata record) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(record.topic(), record.partition()));
            return offset == null ? -1 : offset.offset();
        }
    }
    void committedPast(String group, RecordMetadata record) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(committed(group, record)).isGreaterThan(record.offset()));
    }

    @Test void actualApiOutboxFeedsBothConsumersAndDuplicateDeliveryIsHarmless() throws Exception {
        jdbc.update("INSERT INTO members(id, full_name) VALUES (1, 'A'), (2, 'B') ON CONFLICT DO NOTHING");
        var event = DurableEventConsumerTest.event();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(contract.encode(event));
        node.remove("ingestedAt");
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(node.toString()))
                .andExpect(status().isAccepted());
        appliedBoth(event.eventId());
        var metadata = send(event); // Same producer content, different server receipt time is still a duplicate.
        committedPast(FEATURE, metadata);
        committedPast(CACHE, metadata);
        assertThat(effects(FEATURE, event.eventId())).isEqualTo(1);
        assertThat(effects(CACHE, event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM live_member_event e JOIN live_projection_active a ON e.generation = a.generation WHERE event_id = ?",
                Long.class, event.eventId())).isEqualTo(1);
        assertThat(metrics.get("pymk.events.consumer.process").tag("group", FEATURE).tag("outcome", "committed").timer().count()).isPositive();
        assertThat(metrics.get("pymk.events.outbox.send").tag("outcome", "acknowledged").timer().count()).isPositive();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/metrics/pymk.events.consumer.process"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("pymk.events.consumer.process"));
    }

    @Test void conflictingIdIsDeadLetteredWithMetadataThenPartitionContinues() throws Exception {
        var original = DurableEventConsumerTest.event();
        send(original); appliedBoth(original.eventId());
        var changed = new EventEnvelope(1, original.eventId(), StreamEventType.INVITE_SENT,
                original.occurredAt(), original.ingestedAt(), 1, 2, original.correlationId(), Map.of());
        var record = wire(changed);
        record.headers().add("trace-test", "retained".getBytes(StandardCharsets.UTF_8));
        var metadata = template.send(record).get(20, TimeUnit.SECONDS).getRecordMetadata();
        // Recovery must commit even when the poison record is the final record in the partition.
        committedPast(FEATURE, metadata);
        committedPast(CACHE, metadata);
        var later = DurableEventConsumerTest.event();
        send(later); appliedBoth(later.eventId());
        var dlt = deadLetter(changed.eventId(), FEATURE);
        assertThat(dlt.key()).isEqualTo(record.key());
        assertThat(dlt.value()).isEqualTo(record.value());
        assertThat(header(dlt, "offset")).isEqualTo(Long.toString(metadata.offset()));
        assertThat(header(dlt, "partition")).isEqualTo("0");
        assertThat(header(dlt, "topic")).isEqualTo(record.topic());
        assertThat(header(dlt, "reason")).isEqualTo("IDENTITY_CONFLICT");
        assertThat(dlt.headers().lastHeader("trace-test").value()).isEqualTo("retained".getBytes(StandardCharsets.UTF_8));
        assertThat(deadLetter(changed.eventId(), CACHE)).isNotNull();
        assertThat(effects(FEATURE, original.eventId())).isEqualTo(1);
        assertThat(metrics.get("pymk.events.consumer.retries").tag("group", FEATURE).counter().count()).isGreaterThanOrEqualTo(3);
        assertThat(metrics.get("pymk.events.consumer.dead.letters").tag("group", FEATURE).tag("outcome", "acknowledged").counter().count()).isPositive();
    }

    @Test void brokerAndConsumerRestartsPreservePerKeyOrderAndExposeBacklogWithoutFalseZero() throws Exception {
        jdbc.update("INSERT INTO members(id, full_name) VALUES (35101, 'Restart A'), (35102, 'Restart B') ON CONFLICT DO NOTHING");
        var ids = new ArrayList<UUID>();
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.stopContainerCmd(KAFKA.getContainerId()).withTimeout(5).exec();
        try {
            for (int i = 0; i < 3; i++) ids.add(postRestartEvent());
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() ->
                    assertThat(metrics.find("pymk.events.outbox.send").tag("outcome", "failed").timer()).isNotNull());
            monitor.poll();
            assertThat(gauge("pymk.events.monitor.up", "source", "kafka")).isZero();
            assertThat(gauge("pymk.events.consumer.lag", "group", FEATURE)).isNaN();
            assertThat(metrics.get("pymk.events.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(3);
            assertThat(gauge("pymk.events.projection.pending", "group", FEATURE)).isGreaterThanOrEqualTo(3);
        } finally { docker.startContainerCmd(KAFKA.getContainerId()).exec(); }
        for (UUID id : ids) appliedBoth(id);
        assertThat(jdbc.queryForList("""
                SELECT event_id FROM event_consumer_journal
                WHERE consumer_name = ? AND subject_member_id = 35101 ORDER BY source_offset
                """, UUID.class, FEATURE)).containsExactlyElementsOf(ids);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT source_partition) FROM event_consumer_journal
                WHERE consumer_name = ? AND subject_member_id = 35101
                """, Long.class, FEATURE)).isEqualTo(1);
        feature.stop();
        UUID whileStopped = postRestartEvent();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(effects(CACHE, whileStopped)).isEqualTo(1));
        monitor.poll();
        assertThat(gauge("pymk.events.consumer.lag", "group", FEATURE)).isPositive();
        assertThat(gauge("pymk.events.projection.oldest.age", "group", FEATURE)).isPositive();
        feature.start();
        appliedBoth(whileStopped);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            monitor.poll();
            assertThat(gauge("pymk.events.monitor.up", "source", "kafka")).isEqualTo(1);
            assertThat(gauge("pymk.events.consumer.lag", "group", FEATURE)).isZero();
            assertThat(gauge("pymk.events.projection.pending", "group", FEATURE)).isZero();
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM live_member_event e JOIN live_projection_active a ON e.generation = a.generation
                WHERE subject_member_id = 35101
                """, Long.class)).isEqualTo(4);
    }

    private UUID postRestartEvent() throws Exception {
        var event = new EventEnvelope(1, UUID.randomUUID(), StreamEventType.PROFILE_VIEW,
                java.time.Instant.now().minusSeconds(1), java.time.Instant.now(), 35101, 35102, UUID.randomUUID(), Map.of());
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(contract.encode(event));
        node.remove("ingestedAt");
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(node.toString()))
                .andExpect(status().isAccepted());
        return event.eventId();
    }
    private double gauge(String name, String tag, String value) { return metrics.get(name).tag(tag, value).gauge().value(); }

    @Test void connectionDomainEventReachesBothJournalsWithoutInventingLabels() throws Exception {
        jdbc.update("INSERT INTO members(id, full_name) VALUES (331, 'C'), (332, 'D') ON CONFLICT DO NOTHING");
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":331,\"connectedMemberId\":332}")).andExpect(status().isCreated());
        UUID id = jdbc.queryForObject("SELECT event_id FROM event_outbox WHERE aggregate_key = '331:332'", UUID.class);
        appliedBoth(id);
        assertThat(jdbc.queryForObject("""
                SELECT aggregate_sequence FROM event_consumer_journal WHERE consumer_name = ? AND event_id = ?
                """, Long.class, FEATURE, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_events WHERE actor_member_id = 331", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT aggregate_sequence FROM live_connection_state WHERE first_member_id = 331 AND second_member_id = 332", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT epoch FROM member_cache_epoch WHERE member_id = 331", Long.class)).isEqualTo(1);
    }

    @Test void oversizedPoisonFitsDltWithRecoveryMetadataAndDoesNotBlockFollowingRecords() throws Exception {
        byte[] oversized = new byte[1_048_400];
        Arrays.fill(oversized, (byte) 'x');
        var source = new ProducerRecord<byte[], byte[]>(KafkaTopics.MEMBER_EVENTS, 0,
                "1".getBytes(StandardCharsets.UTF_8), oversized);
        var metadata = template.send(source).get(20, TimeUnit.SECONDS).getRecordMetadata();
        var next = DurableEventConsumerTest.event();
        send(next);
        appliedBoth(next.eventId());
        var dead = deadLetter(record -> FEATURE.equals(header(record, "consumer"))
                && Long.toString(metadata.offset()).equals(header(record, "offset")));
        assertThat(dead.value()).isEqualTo(oversized);
        assertThat(header(dead, "reason")).isEqualTo("INVALID_CONTRACT");
        assertThatThrownBy(() -> DeadLetterReplay.sourceRecord(dead)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void crashAfterDatabaseCommitBeforeOffsetCommitDoesNotDoubleApply() throws Exception {
        feature.stop();
        var target = DurableEventConsumerTest.event();
        var calls = new AtomicInteger();
        var wrapper = new DurableEventConsumer(jdbc) {
            @Override public void accept(String group, ConsumerRecord<byte[], byte[]> record) {
                service.accept(group, record); // Returns after the real Spring transaction commits.
                if (new EventRecordDecoder().decode(record).eventId().equals(target.eventId())
                        && calls.incrementAndGet() == 1) throw new IllegalStateException("simulated post-commit crash");
            }
        };
        var restarted = EventConsumerConfiguration.container(FEATURE, KAFKA.getBootstrapServers(), wrapper, recoverer);
        restarted.start();
        try {
            var metadata = send(target);
            committedPast(FEATURE, metadata);
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
            assertThat(effects(FEATURE, target.eventId())).isEqualTo(1);
        } finally { restarted.stop(); }
        // A fresh listener instance uses committed offsets and the same durable receipt store.
        feature.start();
        var replay = send(target);
        committedPast(FEATURE, replay);
        assertThat(effects(FEATURE, target.eventId())).isEqualTo(1);
    }

    @Test void failedDltPublicationDoesNotAdvanceOffsetAndOriginalIdentityCanBeReplayed() throws Exception {
        feature.stop();
        var target = DurableEventConsumerTest.event();
        var later = DurableEventConsumerTest.event();
        var reject = new AtomicBoolean(true);
        var dltUnavailable = new AtomicBoolean(true);
        var recoveryAttempts = new AtomicInteger();
        var config = new EventConsumerConfiguration();
        var brokenFactory = config.deadLetterProducerFactory("127.0.0.1:1");
        var brokenPublisher = new EventDeadLetterPublisher(config.deadLetterKafkaTemplate(brokenFactory));
        doAnswer(call -> {
            if (FEATURE.equals(call.getArgument(0)) && dltUnavailable.get()) {
                recoveryAttempts.incrementAndGet();
                brokenPublisher.recover(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                return null;
            }
            return call.callRealMethod();
        }).when(recoverer).recover(anyString(), any(), any());
        var attempts = new AtomicInteger();
        var wrapper = new DurableEventConsumer(jdbc) {
            @Override public void accept(String group, ConsumerRecord<byte[], byte[]> record) {
                if (new EventRecordDecoder().decode(record).eventId().equals(target.eventId()) && reject.get()) {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("simulated unavailable projection");
                }
                service.accept(group, record);
            }
        };
        var listener = EventConsumerConfiguration.container(FEATURE, KAFKA.getBootstrapServers(), wrapper, recoverer);
        listener.start();
        try {
            var poisonOffset = send(target);
            var tailOffset = send(later);
            await().atMost(Duration.ofSeconds(30)).until(() -> recoveryAttempts.get() > 0);
            assertThat(attempts.get()).isGreaterThanOrEqualTo(4); // Initial attempt + 3 exponential retries.
            assertThat(committed(FEATURE, poisonOffset)).isLessThanOrEqualTo(poisonOffset.offset());
            assertThat(effects(FEATURE, later.eventId())).isZero(); // The failed record blocks its lane.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(effects(CACHE, later.eventId())).isEqualTo(1)); // Independent group progresses.
            dltUnavailable.set(false);
            committedPast(FEATURE, tailOffset);
            assertThat(effects(FEATURE, target.eventId())).isZero();
            var dead = deadLetter(target.eventId(), FEATURE);
            assertThat(header(dead, "reason")).isEqualTo("PROCESSING_FAILED");
            reject.set(false);
            // Exercise the executable replay path, not a rewritten event or a group-offset reset.
            DeadLetterReplay.main(new String[] {KAFKA.getBootstrapServers(), dead.topic(),
                    Integer.toString(dead.partition()), Long.toString(dead.offset())});
            appliedBoth(target.eventId());
            template.send(DeadLetterReplay.sourceRecord(dead)).get(20, TimeUnit.SECONDS);
            var sentinel = send(DurableEventConsumerTest.event());
            committedPast(FEATURE, sentinel);
            assertThat(effects(FEATURE, target.eventId())).isEqualTo(1);
            assertThat(effects(CACHE, target.eventId())).isEqualTo(1);
        } finally {
            dltUnavailable.set(false);
            listener.stop();
            brokenFactory.destroy();
        }
    }

    ConsumerRecord<byte[], byte[]> deadLetter(UUID eventId, String group) {
        return deadLetter(record -> new String(record.value(), StandardCharsets.UTF_8).contains(eventId.toString())
                && group.equals(header(record, "consumer")));
    }

    ConsumerRecord<byte[], byte[]> deadLetter(java.util.function.Predicate<ConsumerRecord<byte[], byte[]>> matches) {
        try (var reader = new KafkaConsumer<byte[], byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            var tp = new TopicPartition(KafkaTopics.MEMBER_EVENTS_DLT, 0);
            reader.assign(List.of(tp));
            reader.seekToBeginning(List.of(tp));
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (System.nanoTime() < deadline)
                for (var record : reader.poll(Duration.ofMillis(250)))
                    if (matches.test(record)) return record;
        }
        throw new AssertionError("Expected DLT record was not found");
    }
    String header(ConsumerRecord<byte[], byte[]> record, String name) {
        var header = record.headers().lastHeader(EventDeadLetterPublisher.PREFIX + name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
