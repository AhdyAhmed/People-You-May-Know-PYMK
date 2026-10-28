package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The database transaction, not Kafka availability, is the acceptance boundary. */
@Service
public class EventIngestionService {
    public static final long ADMISSION_LOCK = 320032L;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int maxPending;
    private final long futureSkewSeconds;
    private final EventContract contract = new EventContract();

    public EventIngestionService(JdbcTemplate jdbc, Clock clock,
            @Value("${pymk.events.max-pending:100000}") int maxPending,
            @Value("${pymk.events.future-skew-seconds:300}") long futureSkewSeconds) {
        if (maxPending < 1 || futureSkewSeconds < 0) throw new IllegalArgumentException("Invalid event admission settings");
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxPending = maxPending;
        this.futureSkewSeconds = futureSkewSeconds;
    }

    /** Shared by graph writes, member events and destructive development reset. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockAdmission() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ADMISSION_LOCK);
    }

    @Transactional
    public Receipt ingest(EventEnvelope event) {
        if (event.eventType() == StreamEventType.CONNECTION_CREATED)
            throw new EventFailure(400, "Connection events are emitted only by the connection API");
        String fingerprint = contract.contentFingerprint(event);
        lockAdmission();
        var existing = jdbc.query("SELECT content_fingerprint, envelope FROM event_outbox WHERE event_id = ?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, event.eventId());
        if (!existing.isEmpty()) {
            if (!fingerprint.equals(existing.getFirst()[0]))
                throw new EventFailure(409, "Event ID already belongs to different content");
            EventEnvelope original = contract.decode(event.topic(), event.key(), existing.getFirst()[1]);
            return receipt(original);
        }
        Instant now = now();
        if (event.occurredAt().isAfter(now.plusSeconds(futureSkewSeconds)))
            throw new EventFailure(400, "Event occurrence exceeds the permitted future clock skew");
        // Historical imports are allowed; retries are checked before changing reference/time validation.
        Integer members = jdbc.queryForObject("SELECT count(*) FROM members WHERE id IN (?, ?)",
                Integer.class, event.subjectMemberId(), event.targetMemberId());
        if (members == null || members != 2) throw new EventFailure(404, "Event member not found");
        checkCapacity();
        EventEnvelope accepted = new EventEnvelope(1, event.eventId(), event.eventType(), event.occurredAt(),
                now, event.subjectMemberId(), event.targetMemberId(), event.correlationId(), event.payload());
        jdbc.update("INSERT INTO member_events(actor_member_id, target_member_id, type, occurred_at) VALUES (?, ?, ?, ?)",
                accepted.subjectMemberId(), accepted.targetMemberId(), accepted.eventType().name(),
                Timestamp.from(accepted.occurredAt()));
        append(accepted);
        return receipt(accepted);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void connectionCreated(long a, long b, Instant occurredAt) {
        lockAdmission();
        checkCapacity();
        String key = Math.min(a, b) + ":" + Math.max(a, b);
        Long sequence = jdbc.queryForObject("""
                INSERT INTO event_aggregate_sequence(aggregate_key, sequence) VALUES (?, 1)
                ON CONFLICT (aggregate_key) DO UPDATE SET sequence = event_aggregate_sequence.sequence + 1
                RETURNING sequence
                """, Long.class, key);
        append(new EventEnvelope(1, UUID.randomUUID(), StreamEventType.CONNECTION_CREATED, occurredAt,
                now(), Math.min(a, b), Math.max(a, b), UUID.randomUUID(), Map.of("aggregateSequence", sequence)));
    }

    private void checkCapacity() {
        Long pending = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT 1 FROM event_outbox WHERE published_at IS NULL LIMIT ?) pending
                """, Long.class, maxPending);
        if (pending != null && pending >= maxPending)
            throw new EventFailure(503, "Event backlog is full; retry later with the same event ID");
    }

    private void append(EventEnvelope event) {
        jdbc.update("""
                INSERT INTO event_outbox(event_id, content_fingerprint, topic, aggregate_key, envelope, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, event.eventId(), contract.contentFingerprint(event), event.topic(), event.key(),
                contract.encode(event), Timestamp.from(event.ingestedAt()));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private Receipt receipt(EventEnvelope e) { return new Receipt(e.eventId(), e.ingestedAt(), "accepted"); }
    public record Receipt(UUID eventId, Instant ingestedAt, String status) {}
}
