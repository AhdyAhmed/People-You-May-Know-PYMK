package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receipt and normalized journal projection commit together, before the listener returns. */
@Service
public class DurableEventConsumer {
    private final JdbcTemplate jdbc;
    private final EventRecordDecoder decoder = new EventRecordDecoder();
    private final EventContract contract = new EventContract();
    public DurableEventConsumer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 10)
    public void accept(String consumer, ConsumerRecord<byte[], byte[]> record) {
        EventEnvelope event;
        try { event = decoder.decode(record); }
        catch (IllegalArgumentException invalid) {
            throw new ConsumerEventFailure(ConsumerEventFailure.Reason.INVALID_CONTRACT);
        }
        String fingerprint = contract.contentFingerprint(event);
        // A shared policy lock serializes any administrator's replay-floor change with admission.
        Instant floor = jdbc.queryForObject("""
                SELECT min_ingested_at FROM event_consumer_policy WHERE consumer_name = ? FOR SHARE
                """, (rs, n) -> rs.getTimestamp(1).toInstant(), consumer);
        var existing = jdbc.query("""
                SELECT content_fingerprint FROM event_consumer_receipt WHERE consumer_name = ? AND event_id = ?
                """, (rs, n) -> rs.getString(1), consumer, event.eventId());
        if (!existing.isEmpty()) {
            requireSame(existing.getFirst(), fingerprint);
            return;
        }
        if (event.ingestedAt().isBefore(floor))
            throw new ConsumerEventFailure(ConsumerEventFailure.Reason.REPLAY_BEFORE_FLOOR);
        int inserted = jdbc.update("""
                INSERT INTO event_consumer_receipt(consumer_name, event_id, content_fingerprint)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, consumer, event.eventId(), fingerprint);
        if (inserted == 0) {
            requireSame(jdbc.queryForObject("""
                    SELECT content_fingerprint FROM event_consumer_receipt WHERE consumer_name = ? AND event_id = ?
                    """, String.class, consumer, event.eventId()), fingerprint);
            return;
        }
        jdbc.update("""
                INSERT INTO event_consumer_journal(consumer_name, event_id, event_type, subject_member_id,
                    target_member_id, occurred_at, ingested_at, aggregate_sequence, envelope,
                    source_topic, source_partition, source_offset, source_timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, consumer, event.eventId(), event.eventType().name(), event.subjectMemberId(),
                event.targetMemberId(), Timestamp.from(event.occurredAt()), Timestamp.from(event.ingestedAt()),
                event.payload().get("aggregateSequence"), contract.encode(event),
                record.topic(), record.partition(), record.offset(), record.timestamp());
    }

    private void requireSame(String stored, String incoming) {
        if (!incoming.equals(stored))
            throw new ConsumerEventFailure(ConsumerEventFailure.Reason.IDENTITY_CONFLICT);
    }
}
