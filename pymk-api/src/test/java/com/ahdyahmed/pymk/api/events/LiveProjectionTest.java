package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.featurestore.LiveFeatureStore;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.ahdyahmed.pymk.orchestrator.RecommendationCache;
import com.ahdyahmed.pymk.stream.contract.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@MockitoBean(types = LightRankerModel.class)
class LiveProjectionTest {
    static final long A = 883401, B = 883402, C = 883403, D = 883404, OTHER = 883405;
    static final Instant END = Instant.parse("2026-09-30T00:00:00Z");
    static final Instant START = END.minus(30, ChronoUnit.DAYS);
    @Autowired JdbcTemplate jdbc;
    @Autowired DurableEventConsumer consumer;
    @Autowired LiveFeatureStore store;
    @Autowired RecommendationCache cache;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    final EventContract contract = new EventContract();

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM event_consumer_journal");
        jdbc.update("DELETE FROM event_consumer_receipt");
        jdbc.update("DELETE FROM live_member_event");
        jdbc.update("DELETE FROM live_connection_state");
        jdbc.update("DELETE FROM member_cache_epoch");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("UPDATE event_consumer_policy SET min_ingested_at = '1970-01-01T00:00:00Z'");
        for (long id : List.of(A, B, C, D, OTHER))
            jdbc.update("INSERT INTO members(id, full_name) VALUES (?, 'Live test') ON CONFLICT DO NOTHING", id);
    }
    EventEnvelope event(StreamEventType type, Instant occurred, Instant ingested, long sequence) {
        return new EventEnvelope(1, UUID.randomUUID(), type, occurred, ingested, A, B, UUID.randomUUID(),
                type == StreamEventType.CONNECTION_CREATED ? Map.of("aggregateSequence", sequence) : Map.of());
    }
    void consume(EventEnvelope event, String group) {
        consumer.accept(group, new ConsumerRecord<>(event.topic(), 0, 0,
                event.key().getBytes(StandardCharsets.UTF_8), contract.encode(event).getBytes(StandardCharsets.UTF_8)));
    }
    void feature(EventEnvelope event) { consume(event, KafkaTopics.FEATURE_PROJECTION_GROUP); }
    void connection(long sequence) {
        consume(event(StreamEventType.CONNECTION_CREATED, END, END, sequence), KafkaTopics.CACHE_INVALIDATION_GROUP);
    }
    void source(EventEnvelope event) {
        jdbc.update("""
                INSERT INTO event_outbox(event_id, content_fingerprint, topic, aggregate_key, envelope, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId(), contract.contentFingerprint(event), event.topic(), event.key(),
                contract.encode(event), java.sql.Timestamp.from(event.ingestedAt()));
    }
    LiveFeatureStore.Counters counts() { return store.counters(A, B, START, END, END); }

    @Test void countsLateAndOutOfOrderEventsOnceWithoutChangingHistoricalFeatures() {
        long historical = jdbc.queryForObject("SELECT count(*) FROM pymk_features", Long.class);
        long revision = jdbc.queryForObject("SELECT revision FROM pymk_source_revision", Long.class);
        var newer = event(StreamEventType.PROFILE_VIEW, END.minusSeconds(1), END, 0);
        feature(newer); feature(newer);
        feature(event(StreamEventType.PROFILE_VIEW, END.minus(15, ChronoUnit.DAYS), END, 0));
        feature(event(StreamEventType.INVITE_SENT, END.minusSeconds(5), END, 0));
        feature(event(StreamEventType.INVITE_ACCEPTED, END.minusSeconds(4), END, 0));
        assertThat(counts().profileViews()).isEqualTo(2);
        assertThat(counts().invitesSent()).isEqualTo(1);
        assertThat(counts().invitesAccepted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pymk_features", Long.class)).isEqualTo(historical);
        assertThat(jdbc.queryForObject("SELECT revision FROM pymk_source_revision", Long.class)).isEqualTo(revision);
    }

    @Test void exactWindowBoundariesExpiryAndKnowledgeCutoffAreAppliedAtReadTime() {
        feature(event(StreamEventType.PROFILE_VIEW, START, END, 0));
        feature(event(StreamEventType.PROFILE_VIEW, END, END, 0));
        feature(event(StreamEventType.PROFILE_VIEW, END.minusSeconds(1), END.plusSeconds(1), 0));
        feature(event(StreamEventType.PROFILE_VIEW, END.plusSeconds(1), END, 0));
        assertThat(counts().profileViews()).isEqualTo(1);
        assertThat(store.counters(A, B, START, END, END.plusSeconds(1)).profileViews()).isEqualTo(2);
        assertThat(store.counters(A, B, END.plusSeconds(1), END.plus(31, ChronoUnit.DAYS), END.plus(31, ChronoUnit.DAYS))
                .profileViews()).isZero();
    }

    @Test void newerConnectionSequenceWinsAndInvalidatesOnlyAffectedNeighborhood() {
        jdbc.update("INSERT INTO connections(member_id, connected_member_id) VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING", A, C, C, D);
        connection(2);
        connection(1); connection(2);
        assertThat(jdbc.queryForList("SELECT member_id FROM member_cache_epoch ORDER BY member_id", Long.class))
                .containsExactly(A, B, C, D);
        assertThat(jdbc.queryForObject("SELECT max(epoch) FROM member_cache_epoch", Long.class)).isEqualTo(1);
        connection(3);
        assertThat(jdbc.queryForObject("SELECT aggregate_sequence FROM live_connection_state", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT min(epoch) FROM member_cache_epoch", Long.class)).isEqualTo(2);
    }

    @Test void inFlightCacheWriteCannotRepopulateNewEpochAndUnrelatedMemberStaysCached() {
        cache.putCandidates(OTHER, List.of());
        try (var scope = cache.pinNamespace()) {
            cache.putCandidates(A, List.of());
            connection(1);
            cache.putCandidates(A, List.of()); // Simulate old in-flight computation finishing after invalidation.
            assertThat(cache.getCandidates(A)).isPresent();
        }
        assertThat(cache.getCandidates(A)).isEmpty();
        assertThat(cache.getCandidates(OTHER)).isPresent();
    }

    @Test void rollbackLeavesNeitherProjectionNorCacheVersion() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            feature(event(StreamEventType.PROFILE_VIEW, END, END, 0)); connection(1);
            tx.setRollbackOnly();
        });
        assertThat(counts().profileViews()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_cache_epoch", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_consumer_receipt", Long.class)).isZero();
    }

    @Test void reconciliationRecoversUnpublishedSourceAndReplayDoesNotDoubleCount() {
        var event = event(StreamEventType.PROFILE_VIEW, END.minusSeconds(10), END, 0);
        var edge = event(StreamEventType.CONNECTION_CREATED, END, END, 7);
        source(event); source(edge);
        long previous = counts().generation();
        long next = store.reconcile(START, END, END);
        assertThat(next).isGreaterThan(previous);
        assertThat(counts().generation()).isEqualTo(next);
        assertThat(counts().profileViews()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT epoch FROM member_cache_epoch WHERE member_id = ?", Long.class, A)).isEqualTo(1);
        feature(event); consume(edge, KafkaTopics.CACHE_INVALIDATION_GROUP);
        assertThat(counts().profileViews()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT epoch FROM member_cache_epoch WHERE member_id = ?", Long.class, A)).isEqualTo(1);
        store.reconcile(START, END, END);
        assertThat(counts().profileViews()).isEqualTo(1);
    }

    @Test void reconciliationPreservesNewerKnowledgeAndAtomicGenerationOnFailure() {
        feature(event(StreamEventType.PROFILE_VIEW, END.minusSeconds(1), END.plusSeconds(1), 0));
        long previous = counts().generation();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            store.reconcile(START, END, END); tx.setRollbackOnly();
        });
        assertThat(counts().generation()).isEqualTo(previous);
        store.reconcile(START, END, END);
        assertThat(store.counters(A, B, START, END, END.plusSeconds(1)).profileViews()).isEqualTo(1);
    }

    @Test void actualHttpEndpointReadsLiveCountersAndValidatesMembers() throws Exception {
        var now = Instant.now().minusSeconds(1);
        feature(event(StreamEventType.PROFILE_VIEW, now, now, 0));
        mvc.perform(get("/api/v1/members/{id}/live-features/{candidateId}", A, B))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileViews").value(1));
        mvc.perform(get("/api/v1/members/{id}/live-features/{candidateId}", A, A)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/members/{id}/live-features/{candidateId}", A, 999999999)).andExpect(status().isNotFound());
    }

    @Test void concurrentDeliveryWaitsForGenerationSwapAndWritesToNewGeneration() throws Exception {
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var repair = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                long generation = store.reconcile(START, END, END);
                locked.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return generation;
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var delivery = pool.submit(() -> feature(event(StreamEventType.PROFILE_VIEW, END, END, 0)));
            try {
                assertThatThrownBy(() -> delivery.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            long generation = repair.get(10, java.util.concurrent.TimeUnit.SECONDS);
            delivery.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(counts().generation()).isEqualTo(generation);
            assertThat(counts().profileViews()).isEqualTo(1);
        } finally { release.countDown(); }
    }
}
