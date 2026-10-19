package com.ahdyahmed.pymk.domain.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A shared transaction lock pins the release for a complete request, including L0 workers. */
@Service
public class ServingReleaseStore {
    public static final long LOCK_ID = 230023L;
    private final JdbcTemplate jdbc;
    private final ThreadLocal<ActiveRelease> pinned = new ThreadLocal<>();

    public record ActiveRelease(String id, long epoch, String modelVersion) {
        public boolean managed() { return id != null; }
        public String namespace() { return (id == null ? "legacy" : id) + ":" + epoch; }
    }

    public ServingReleaseStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ActiveRelease current() {
        ActiveRelease selected = pinned.get();
        return selected != null ? selected : jdbc.queryForObject("""
                SELECT a.release_id, a.epoch, COALESCE(r.model_version, (SELECT model_version FROM pymk_legacy_release_model))
                FROM pymk_active_release a LEFT JOIN pymk_serving_releases r USING (release_id)
                """, (rs, n) -> new ActiveRelease(rs.getString(1), rs.getLong(2), rs.getString(3)));
    }

    public Scope pin() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Release pinning requires a transaction");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(?)", Object.class, LOCK_ID);
        ActiveRelease previous = pinned.get();
        pinned.set(current());
        return () -> { if (previous == null) pinned.remove(); else pinned.set(previous); };
    }

    public void verifyCandidateBudgets(int graph, int heuristic, int embedding) {
        var active = current();
        if (!active.managed()) return;
        boolean covered = jdbc.queryForObject("""
                SELECT (manifest->'candidateLimits'->>'graph')::int >= ?
                   AND (manifest->'candidateLimits'->>'heuristic')::int >= ?
                   AND (manifest->'candidateLimits'->>'embedding')::int >= ?
                FROM pymk_serving_releases WHERE release_id=?
                """, Boolean.class, graph, heuristic, embedding, active.id());
        if (!covered) throw new IllegalStateException("Online candidate budgets exceed the release feature coverage");
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
}
