package com.ahdyahmed.pymk.api.events;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded head-of-key claims. A row lock is held through send + marker, so an expired
 * lease cannot cause concurrent sends for that row. Delivery is at least once.
 */
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Publisher publisher;
    private final int batchSize;
    private final int leaseSeconds;
    private final int retrySeconds;

    public OutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager manager, Publisher publisher,
            int batchSize, int leaseSeconds, int retrySeconds) {
        if (batchSize < 1 || batchSize > 1000 || leaseSeconds < 1 || retrySeconds < 1)
            throw new IllegalArgumentException("Invalid outbox relay bounds");
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.retrySeconds = retrySeconds;
    }

    public List<Lease> claim() {
        UUID owner = UUID.randomUUID();
        return tx.execute(status -> jdbc.query("""
                WITH candidates AS (
                    SELECT o.id FROM event_outbox o
                    WHERE o.published_at IS NULL AND o.available_at <= clock_timestamp()
                      AND (o.lease_until IS NULL OR o.lease_until < clock_timestamp())
                      AND NOT EXISTS (
                        SELECT 1 FROM event_outbox earlier
                        WHERE earlier.topic = o.topic AND earlier.aggregate_key = o.aggregate_key
                          AND earlier.id < o.id AND earlier.published_at IS NULL)
                    ORDER BY o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                )
                UPDATE event_outbox o SET lease_owner = ?,
                    lease_until = clock_timestamp() + make_interval(secs => ?)
                FROM candidates c WHERE o.id = c.id RETURNING o.id, o.lease_owner
                """, (rs, n) -> new Lease(rs.getLong(1), rs.getObject(2, UUID.class)),
                batchSize, owner, leaseSeconds));
    }

    public void publish(Lease lease) {
        tx.executeWithoutResult(status -> {
            var rows = jdbc.query("""
                    SELECT topic, aggregate_key, envelope FROM event_outbox
                    WHERE id = ? AND lease_owner = ? AND published_at IS NULL
                    FOR UPDATE SKIP LOCKED
                    """, (rs, n) -> new Message(rs.getString(1), rs.getString(2), rs.getString(3)),
                    lease.id(), lease.owner());
            if (rows.isEmpty()) return; // Reclaimed lease or another worker owns the row lock.
            Message message = rows.getFirst();
            try {
                publisher.send(message); // Returns only after broker acknowledgement.
            } catch (RuntimeException failed) {
                jdbc.update("""
                        UPDATE event_outbox SET attempts = LEAST(attempts::bigint + 1, 2147483647)::integer,
                            available_at = clock_timestamp() + make_interval(secs => ?),
                            lease_owner = NULL, lease_until = NULL WHERE id = ? AND lease_owner = ?
                        """, retrySeconds, lease.id(), lease.owner());
                org.slf4j.LoggerFactory.getLogger(OutboxRelay.class).warn(
                        "Outbox send failed; record retained for retry (cause: {})", failed.getClass().getSimpleName());
                return;
            }
            // A process/database failure here leaves the exact envelope pending for replay.
            jdbc.update("""
                    UPDATE event_outbox SET published_at = clock_timestamp(),
                        attempts = LEAST(attempts::bigint + 1, 2147483647)::integer,
                        lease_owner = NULL, lease_until = NULL WHERE id = ? AND lease_owner = ?
                    """, lease.id(), lease.owner());
        });
    }

    public void tick() { for (Lease lease : claim()) publish(lease); }
    public record Lease(long id, UUID owner) {}
    public record Message(String topic, String key, String json) {}
    @FunctionalInterface public interface Publisher { void send(Message message); }
}
