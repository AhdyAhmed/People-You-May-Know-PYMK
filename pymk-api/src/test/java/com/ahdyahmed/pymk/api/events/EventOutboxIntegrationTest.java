package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.ahdyahmed.pymk.stream.contract.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "pymk.events.max-pending=3")
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@MockitoBean(types = LightRankerModel.class)
@Testcontainers
class EventOutboxIntegrationTest {
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventIngestionService ingestion;
    @Autowired PlatformTransactionManager manager;
    final EventContract contract = new EventContract();

    @BeforeEach void seed() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_marker_failure ON event_outbox");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM event_aggregate_sequence");
        jdbc.update("DELETE FROM member_events");
        jdbc.update("DELETE FROM connections");
        jdbc.update("INSERT INTO members(id, full_name) VALUES (910001, 'A'), (910002, 'B'), (910003, 'C') ON CONFLICT DO NOTHING");
    }

    EventEnvelope event(long actor) {
        return new EventEnvelope(1, UUID.randomUUID(), StreamEventType.PROFILE_VIEW,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.now(), actor, 910003, UUID.randomUUID(), Map.of());
    }
    String request(EventEnvelope e) throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(contract.encode(e));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("ingestedAt");
        return node.toString();
    }
    org.springframework.test.web.servlet.ResultActions postEvent(String json) throws Exception {
        return mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(json));
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    OutboxRelay relay(OutboxRelay.Publisher p, int size) { return new OutboxRelay(jdbc, manager, p, size, 60, 5); }
    void retryNow() {
        jdbc.update("UPDATE event_outbox SET available_at = now() - interval '1 minute', lease_until = CASE WHEN lease_owner IS NOT NULL THEN now() - interval '1 minute' END");
    }

    @Test void durableAcceptanceAndIdenticalRetryReturnOriginalReceipt() throws Exception {
        var e = event(910001);
        String body = request(e);
        String receipt = postEvent(body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String duplicate = postEvent(body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(duplicate).isEqualTo(receipt);
        assertThat(count("member_events")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM event_outbox", Boolean.class)).isTrue();
        postEvent(body.replace("PROFILE_VIEW", "INVITE_SENT")).andExpect(status().isConflict());
        relay(m -> {}, 1).tick();
        assertThat(postEvent(body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .isEqualTo(receipt);
        assertThat(count("member_events")).isEqualTo(1);
    }

    @Test void validatesReferencesClockSkewAndClosedPayload() throws Exception {
        String body = request(event(910001));
        postEvent(body.replace("910003", "999999")).andExpect(status().isNotFound());
        postEvent(body.replace("2026-01-01T00:00:00Z", "9999-01-01T00:00:00Z")).andExpect(status().isBadRequest());
        postEvent(body.replace("\"payload\":{}", "\"payload\":{\"unexpected\":1}")).andExpect(status().isBadRequest());
        postEvent(body.replace("PROFILE_VIEW", "CONNECTION_CREATED")).andExpect(status().isBadRequest());
        postEvent(body.replace("910003", "910001")).andExpect(status().isBadRequest());
        postEvent(body.replace("910001", "910001.0")).andExpect(status().isBadRequest());
        postEvent(body.replace("\"schemaVersion\":1", "\"schemaVersion\":2")).andExpect(status().isBadRequest());
        postEvent(body.substring(0, body.length() - 1) + ",\"ingestedAt\":\"2026-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest());
        postEvent(body + "{}").andExpect(status().isBadRequest());
        postEvent(body.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))
                .andExpect(status().isBadRequest());
        postEvent(" ".repeat(EventContract.MAX_BYTES + 1)).andExpect(status().isPayloadTooLarge());
        assertThat(count("member_events")).isZero();
        assertThat(count("event_outbox")).isZero();
    }

    @Test void everyMemberEventTypeIsAcceptedWithoutChangingGraph() throws Exception {
        for (var type : StreamEventType.values()) {
            if (type == StreamEventType.CONNECTION_CREATED) continue;
            postEvent(request(event(910001)).replace("PROFILE_VIEW", type.name())).andExpect(status().isAccepted());
            relay(m -> {}, 1).tick();
        }
        assertThat(count("member_events")).isEqualTo(5);
        assertThat(count("connections")).isZero();
    }

    @Test void backlogRejectsNewEventsButAllowsReceiptsAndRollsBackGraph() throws Exception {
        var first = event(910001);
        ingestion.ingest(first);
        ingestion.ingest(event(910001));
        ingestion.ingest(event(910002));
        postEvent(request(event(910001))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
        postEvent(request(first)).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":910001,\"connectedMemberId\":910002}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(count("connections")).isZero();
        assertThat(count("member_events")).isEqualTo(3);
        assertThat(count("event_outbox")).isEqualTo(3);
        relay(m -> {}, 3).tick();
        postEvent(request(event(910001))).andExpect(status().isAccepted());
    }

    @Test void connectionEmitsOneCanonicalDomainEventAndNoTrainingLabel() throws Exception {
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":910002,\"connectedMemberId\":910001}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":910001,\"connectedMemberId\":910002}")).andExpect(status().isOk());
        assertThat(count("connections")).isEqualTo(2);
        assertThat(count("member_events")).isZero();
        assertThat(count("event_outbox")).isEqualTo(1);
        var e = contract.decode(KafkaTopics.CONNECTION_EVENTS, "910001:910002",
                jdbc.queryForObject("SELECT envelope FROM event_outbox", String.class));
        assertThat(e.eventType()).isEqualTo(StreamEventType.CONNECTION_CREATED);
        assertThat(e.payload()).containsEntry("aggregateSequence", 1L);
        jdbc.update("DELETE FROM connections");
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":910001,\"connectedMemberId\":910002}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT sequence FROM event_aggregate_sequence", Long.class)).isEqualTo(2);
    }

    @Test void rollbackLeavesNeitherDomainEventNorOutbox() {
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            ingestion.ingest(event(910001));
            s.setRollbackOnly();
        });
        assertThat(count("member_events")).isZero();
        assertThat(count("event_outbox")).isZero();
    }

    @Test void concurrentSameIdProducesOnlyOneReceipt() throws Exception {
        var e = event(910001);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<EventIngestionService.Receipt>>();
            for (int i = 0; i < 6; i++) tasks.add(() -> ingestion.ingest(e));
            var receipts = pool.invokeAll(tasks);
            var first = receipts.getFirst().get();
            for (var receipt : receipts) assertThat(receipt.get()).isEqualTo(first);
        }
        assertThat(count("member_events")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test void concurrentAdmissionCannotExceedBacklogLimit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 6; i++) tasks.add(() -> {
                try { ingestion.ingest(event(910001)); return true; }
                catch (EventFailure full) { assertThat(full.status()).isEqualTo(503); return false; }
            });
            long accepted = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(3);
        }
        assertThat(count("member_events")).isEqualTo(3);
        assertThat(count("event_outbox")).isEqualTo(3);
    }

    @Test void failedHeadBlocksOnlyItsOwnKeyAndLeasesRecover() {
        var a = event(910001);
        var b = event(910001);
        var c = event(910002);
        ingestion.ingest(a); ingestion.ingest(b); ingestion.ingest(c);
        List<String> delivered = new ArrayList<>();
        var broken = relay(m -> { throw new IllegalStateException("broker unavailable"); }, 1);
        var firstLease = broken.claim().getFirst();
        assertThat(broken.claim()).hasSize(1); // Other key, never later same-key record.
        broken.publish(firstLease);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE published_at IS NOT NULL", Long.class))
                .isZero();
        retryNow();
        var recovered = relay(m -> delivered.add(m.json()), 2);
        var leases = recovered.claim();
        assertThat(leases).hasSize(2);
        for (var lease : leases) recovered.publish(lease);
        recovered.publish(firstLease); // stale owner cannot publish again
        recovered.tick();
        assertThat(delivered).hasSize(3);
        assertThat(delivered.stream().filter(j -> j.contains("\"subjectMemberId\":910001")).toList())
                .containsExactly(contract.encode(withReceipt(a)), contract.encode(withReceipt(b)));
    }

    EventEnvelope withReceipt(EventEnvelope e) {
        return contract.decode(e.topic(), e.key(),
                jdbc.queryForObject("SELECT envelope FROM event_outbox WHERE event_id = ?", String.class, e.eventId()));
    }

    @Test void crashAfterAckBeforeMarkerReplaysExactIdentity() {
        ingestion.ingest(event(910001));
        List<String> sent = new ArrayList<>();
        var relay = relay(m -> sent.add(m.json()), 1);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_marker() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated crash before publication marker'; END $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER test_marker_failure BEFORE UPDATE OF published_at ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION test_fail_marker()
                """);
        assertThatThrownBy(relay::tick).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(sent).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM event_outbox", Boolean.class)).isTrue();
        jdbc.execute("DROP TRIGGER test_marker_failure ON event_outbox");
        retryNow();
        relay.tick();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(sent.getFirst());
    }

    @Test void concurrentRelaysDoNotSendAnExpiredButLockedRow() throws Exception {
        ingestion.ingest(event(910001));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var relay = relay(m -> {
            sending.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        }, 1);
        var lease = relay.claim().getFirst();
        retryNow(); // Expired, but original owner still allowed until another worker claims.
        try (var pool = Executors.newSingleThreadExecutor()) {
            var task = pool.submit(() -> relay.publish(lease));
            try {
                assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(relay.claim()).isEmpty();
            } finally { release.countDown(); }
            task.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void realBrokerReceivesAcknowledgedOutboxEnvelopes() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(KafkaTopics.developmentTopics()).all().get(20, TimeUnit.SECONDS);
        }
        var e = event(910001);
        ingestion.ingest(e);
        var config = new OutboxRelayConfiguration();
        var unavailable = config.outboxProducerFactory("127.0.0.1:1");
        try {
            config.outboxRelay(jdbc, manager, unavailable, config.outboxKafkaTemplate(unavailable), 2, 60, 5).tick();
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL AND attempts = 1 FROM event_outbox", Boolean.class))
                    .isTrue();
        } finally { unavailable.destroy(); }
        retryNow();
        var factory = config.outboxProducerFactory(KAFKA.getBootstrapServers());
        try {
            config.outboxRelay(jdbc, manager, factory, config.outboxKafkaTemplate(factory), 2, 60, 5).tick();
            assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM event_outbox", Boolean.class)).isTrue();
            try (var consumer = new KafkaConsumer<String, String>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "day32-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
                consumer.subscribe(List.of(KafkaTopics.MEMBER_EVENTS));
                List<ConsumerRecord<String, String>> records = new ArrayList<>();
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (records.isEmpty() && System.nanoTime() < deadline)
                    consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                assertThat(records).hasSize(1);
                assertThat(contract.decode(records.getFirst().topic(), records.getFirst().key(), records.getFirst().value()).eventId())
                        .isEqualTo(e.eventId());
            }
        } finally { factory.destroy(); }
    }
}
