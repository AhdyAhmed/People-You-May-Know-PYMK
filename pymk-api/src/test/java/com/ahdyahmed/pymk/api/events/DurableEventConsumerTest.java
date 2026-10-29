package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.*;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.ahdyahmed.pymk.stream.contract.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresTestConfig.class)
@MockitoBean(types = LightRankerModel.class)
class DurableEventConsumerTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DurableEventConsumer consumer;
    @Autowired PlatformTransactionManager manager;
    private static final String GROUP = KafkaTopics.FEATURE_PROJECTION_GROUP;
    final EventContract contract = new EventContract();

    @BeforeEach void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_journal_failure ON event_consumer_journal");
        jdbc.update("DELETE FROM event_consumer_journal");
        jdbc.update("DELETE FROM event_consumer_receipt");
        jdbc.update("UPDATE event_consumer_policy SET min_ingested_at = '1970-01-01T00:00:00Z'");
    }

    static EventEnvelope event() {
        return new EventEnvelope(1, UUID.randomUUID(), StreamEventType.PROFILE_VIEW,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                1, 2, UUID.randomUUID(), Map.of());
    }
    ConsumerRecord<byte[], byte[]> record(EventEnvelope e) {
        return new ConsumerRecord<>(e.topic(), 0, 7, e.key().getBytes(StandardCharsets.UTF_8),
                contract.encode(e).getBytes(StandardCharsets.UTF_8));
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    @Test void duplicatesAndIndependentGroupsHaveExactlyOneJournalEffectEach() {
        var event = event();
        consumer.accept(GROUP, record(event));
        consumer.accept(GROUP, record(event));
        consumer.accept(KafkaTopics.CACHE_INVALIDATION_GROUP, record(event));
        assertThat(count("event_consumer_receipt")).isEqualTo(2);
        assertThat(count("event_consumer_journal")).isEqualTo(2);
    }

    @Test void conflictingIdentityNeverReplacesOriginalProjection() {
        var original = event();
        consumer.accept(GROUP, record(original));
        var changed = new EventEnvelope(1, original.eventId(), StreamEventType.INVITE_SENT,
                original.occurredAt(), original.ingestedAt(), 1, 2, original.correlationId(), Map.of());
        assertThatThrownBy(() -> consumer.accept(GROUP, record(changed)))
                .isInstanceOf(ConsumerEventFailure.class).hasMessage("IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT event_type FROM event_consumer_journal", String.class))
                .isEqualTo("PROFILE_VIEW");
    }

    @Test void concurrentDuplicateDeliveryCannotDoubleApply() throws Exception {
        var event = record(event());
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 6; i++) tasks.add(() -> { consumer.accept(GROUP, event); return null; });
            for (var result : pool.invokeAll(tasks)) result.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("event_consumer_receipt")).isEqualTo(1);
        assertThat(count("event_consumer_journal")).isEqualTo(1);
    }

    @Test void projectionFailureRollsBackReceiptAndRetryCanSucceed() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_journal() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated projection failure'; END $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER test_journal_failure BEFORE INSERT ON event_consumer_journal
                FOR EACH ROW EXECUTE FUNCTION test_fail_journal()
                """);
        var event = record(event());
        assertThatThrownBy(() -> consumer.accept(GROUP, event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("event_consumer_receipt")).isZero();
        assertThat(count("event_consumer_journal")).isZero();
        jdbc.execute("DROP TRIGGER test_journal_failure ON event_consumer_journal");
        consumer.accept(GROUP, event);
        assertThat(count("event_consumer_journal")).isEqualTo(1);
    }

    @Test void crashBeforeCommitLeavesNoReceiptOrEffect() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            consumer.accept(GROUP, record(event()));
            tx.setRollbackOnly();
        });
        assertThat(count("event_consumer_receipt")).isZero();
        assertThat(count("event_consumer_journal")).isZero();
    }

    @Test void retainedReceiptsPreventReplayBeyondThirtyDaysAndFloorGuardsMissingHistory() {
        var old = record(event());
        consumer.accept(GROUP, old);
        jdbc.update("UPDATE event_consumer_policy SET min_ingested_at = '2026-06-01T00:00:00Z'");
        consumer.accept(GROUP, old); // Known receipt still safe beyond source/DLT retention.
        assertThat(count("event_consumer_journal")).isEqualTo(1);
        assertThatThrownBy(() -> consumer.accept(GROUP, record(event())))
                .isInstanceOf(ConsumerEventFailure.class).hasMessage("REPLAY_BEFORE_FLOOR");
        assertThat(count("event_consumer_receipt")).isEqualTo(1);
    }

    @Test void validLateOccurrenceUsesIngestionBoundaryNotEventTime() {
        jdbc.update("UPDATE event_consumer_policy SET min_ingested_at = '2026-01-02T00:00:00Z'");
        consumer.accept(GROUP, record(event()));
        assertThat(count("event_consumer_journal")).isEqualTo(1);
    }

    @Test void malformedUtf8TombstonesAndWrongKeysHaveNoDatabaseEffects() {
        var good = record(event());
        for (var bad : List.of(
                new ConsumerRecord<byte[], byte[]>(good.topic(), 0, 1, good.key(), new byte[]{(byte) 0xff}),
                new ConsumerRecord<byte[], byte[]>(good.topic(), 0, 1, good.key(), null),
                new ConsumerRecord<byte[], byte[]>(good.topic(), 0, 1, "99".getBytes(StandardCharsets.UTF_8), good.value()))) {
            assertThatThrownBy(() -> consumer.accept(GROUP, bad))
                    .isInstanceOf(ConsumerEventFailure.class).hasMessage("INVALID_CONTRACT");
        }
        assertThat(count("event_consumer_receipt")).isZero();
    }
}
