package com.ahdyahmed.pymk.featurestore;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Live event-time windows; never reads or writes a historical model feature snapshot. */
@Service
public class LiveFeatureStore {
    private final JdbcTemplate jdbc;
    public LiveFeatureStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Counters(long generation, long memberId, long candidateId, Instant windowStart,
                           Instant asOf, Instant knowledgeCutoff, long profileViews,
                           long invitesSent, long invitesAccepted) {}

    public Counters counters(long member, long candidate, Instant start, Instant end, Instant knowledgeCutoff) {
        validateWindow(start, end, knowledgeCutoff);
        if (member <= 0 || candidate <= 0 || member == candidate)
            throw new IllegalArgumentException("A live pair requires distinct positive member IDs");
        return jdbc.queryForObject("""
                SELECT a.generation,
                    count(e.event_id) FILTER (WHERE e.event_type = 'PROFILE_VIEW') AS views,
                    count(e.event_id) FILTER (WHERE e.event_type = 'INVITE_SENT') AS sent,
                    count(e.event_id) FILTER (WHERE e.event_type = 'INVITE_ACCEPTED') AS accepted
                FROM live_projection_active a LEFT JOIN live_member_event e
                    ON e.generation = a.generation AND e.subject_member_id = ? AND e.target_member_id = ?
                    AND e.occurred_at > ? AND e.occurred_at <= ? AND e.ingested_at <= ?
                GROUP BY a.generation
                """, (rs, n) -> new Counters(rs.getLong("generation"), member, candidate, start, end,
                knowledgeCutoff, rs.getLong("views"), rs.getLong("sent"), rs.getLong("accepted")),
                member, candidate, Timestamp.from(start), Timestamp.from(end), Timestamp.from(knowledgeCutoff));
    }

    /** Finite, atomic replacement of one recent window, retaining state outside that window. */
    @Transactional(timeout = 120)
    public long reconcile(Instant start, Instant end, Instant knowledgeCutoff) {
        validateWindow(start, end, knowledgeCutoff);
        // Same order as API admission. A completed snapshot never includes a partial source transaction.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(320032)", Object.class);
        long previous = jdbc.queryForObject(
                "SELECT generation FROM live_projection_active WHERE singleton FOR UPDATE", Long.class);
        long generation = jdbc.queryForObject("""
                INSERT INTO live_projection_generation(knowledge_cutoff, window_start, window_end)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, Timestamp.from(knowledgeCutoff), Timestamp.from(start), Timestamp.from(end));
        // A knowledge cutoff is a repair boundary, not permission to discard later-known events.
        jdbc.update("""
                INSERT INTO live_member_event
                SELECT ?, event_id, event_type, subject_member_id, target_member_id, occurred_at, ingested_at
                FROM live_member_event WHERE generation = ?
                    AND NOT (occurred_at > ? AND occurred_at <= ? AND ingested_at <= ?)
                """, generation, previous, Timestamp.from(start), Timestamp.from(end), Timestamp.from(knowledgeCutoff));
        jdbc.update("""
                INSERT INTO live_member_event
                SELECT ?, event_id, event_type, subject_member_id, target_member_id, occurred_at, ingested_at
                FROM (
                    SELECT DISTINCT ON (event_id) event_id, event_type, subject_member_id,
                        target_member_id, occurred_at, ingested_at
                    FROM (
                        SELECT event_id, event_type, subject_member_id, target_member_id, occurred_at,
                            ingested_at, 1 AS priority FROM event_consumer_journal
                            WHERE consumer_name = 'pymk.feature-projection.v1'
                        UNION ALL
                        SELECT event_id, envelope::jsonb->>'eventType',
                            (envelope::jsonb->>'subjectMemberId')::bigint,
                            (envelope::jsonb->>'targetMemberId')::bigint,
                            (envelope::jsonb->>'occurredAt')::timestamptz, ingested_at, 0
                        FROM event_outbox
                    ) source
                    WHERE event_type IN ('PROFILE_VIEW', 'INVITE_SENT', 'INVITE_ACCEPTED')
                        AND occurred_at > ? AND occurred_at <= ? AND ingested_at <= ?
                    ORDER BY event_id, priority
                ) canonical ON CONFLICT DO NOTHING
                """, generation, Timestamp.from(start), Timestamp.from(end), Timestamp.from(knowledgeCutoff));
        // Recover invalidations even while Kafka is down. Sequence comparison makes later replay a no-op.
        jdbc.query("""
                SELECT apply_live_connection((envelope::jsonb->>'subjectMemberId')::bigint,
                    (envelope::jsonb->>'targetMemberId')::bigint,
                    (envelope::jsonb->'payload'->>'aggregateSequence')::bigint,
                    (envelope::jsonb->>'occurredAt')::timestamptz, ingested_at)
                FROM event_outbox WHERE envelope::jsonb->>'eventType' = 'CONNECTION_CREATED'
                    AND ingested_at <= ? ORDER BY id
                """, rs -> { while (rs.next()) { /* execute each durable invalidation */ } return null; },
                Timestamp.from(knowledgeCutoff));
        jdbc.update("UPDATE live_projection_active SET generation = ? WHERE singleton", generation);
        return generation;
    }

    private static void validateWindow(Instant start, Instant end, Instant cutoff) {
        if (start == null || end == null || cutoff == null || !start.isBefore(end))
            throw new IllegalArgumentException("An increasing event-time window and knowledge cutoff are required");
    }
}
