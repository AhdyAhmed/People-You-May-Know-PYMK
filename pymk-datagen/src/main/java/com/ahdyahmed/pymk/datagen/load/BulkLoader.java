package com.ahdyahmed.pymk.datagen.load;

import com.ahdyahmed.pymk.datagen.generate.ConnectionTimelineGenerator.TimestampedEdge;
import com.ahdyahmed.pymk.datagen.generate.EventGenerator.EventRecord;
import com.ahdyahmed.pymk.datagen.generate.MemberFactory.MemberRecord;
import com.ahdyahmed.pymk.domain.vector.VectorLiterals;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Chunked JDBC batch inserts. Plain JdbcTemplate rather than JPA:
 * hydrating/managing ~100K+ entities through the persistence context for a
 * one-shot bulk load would cost far more time and memory than it buys.
 */
@Component
public class BulkLoader {

    private static final int BATCH_SIZE = 5_000;
    private static final Logger log = LoggerFactory.getLogger(BulkLoader.class);

    private final JdbcTemplate jdbc;

    public BulkLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void truncateAll() {
        log.info("Truncating member_events, connections, member_embeddings, members ...");
        jdbc.execute("TRUNCATE TABLE member_events, connections, member_embeddings, members RESTART IDENTITY CASCADE");
    }

    public void insertMembers(List<MemberRecord> members) {
        List<Object[]> rows = new ArrayList<>(members.size());
        for (MemberRecord m : members) {
            rows.add(new Object[] {
                    m.id(), m.fullName(), m.headline(), m.company(), m.school(),
                    m.geoRegion(), Timestamp.from(m.createdAt())
            });
        }
        executeBatched(
                "INSERT INTO members (id, full_name, headline, company, school, geo_region, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                rows, "members");
    }

    /** Writes both directed rows per undirected edge, matching the symmetric edge-list schema. */
    public void insertConnections(List<TimestampedEdge> edges) {
        List<Object[]> rows = new ArrayList<>(edges.size() * 2);
        for (TimestampedEdge te : edges) {
            Timestamp ts = Timestamp.from(te.connectedAt());
            rows.add(new Object[] {te.edge().memberA(), te.edge().memberB(), ts});
            rows.add(new Object[] {te.edge().memberB(), te.edge().memberA(), ts});
        }
        executeBatched(
                "INSERT INTO connections (member_id, connected_member_id, connected_at) VALUES (?,?,?)",
                rows, "connections");
    }

    public void insertEvents(List<EventRecord> events) {
        List<Object[]> rows = new ArrayList<>(events.size());
        for (EventRecord e : events) {
            rows.add(new Object[] {e.actorMemberId(), e.targetMemberId(), e.type().name(), Timestamp.from(e.occurredAt())});
        }
        executeBatched(
                "INSERT INTO member_events (actor_member_id, target_member_id, type, occurred_at) VALUES (?,?,?,?)",
                rows, "member_events");
    }

    /**
     * Bulk-writes placeholder embeddings. Binds each vector as a plain text
     * literal and casts server-side with {@code CAST(? AS vector)} - see
     * {@link VectorLiterals} for why that avoids any pgvector-specific JDBC
     * type registration.
     */
    public void insertEmbeddings(List<MemberEmbeddingRecord> embeddings) {
        List<Object[]> rows = new ArrayList<>(embeddings.size());
        for (MemberEmbeddingRecord e : embeddings) {
            rows.add(new Object[] {e.memberId(), VectorLiterals.toLiteral(e.embedding()), Timestamp.from(e.updatedAt())});
        }
        executeBatched(
                "INSERT INTO member_embeddings (member_id, embedding, updated_at) VALUES (?, CAST(? AS vector), ?)",
                rows, "member_embeddings");
    }

    public record MemberEmbeddingRecord(long memberId, float[] embedding, Instant updatedAt) {
    }

    private void executeBatched(String sql, List<Object[]> rows, String label) {
        int total = rows.size();
        int done = 0;
        for (int start = 0; start < total; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, total);
            jdbc.batchUpdate(sql, rows.subList(start, end));
            done = end;
            log.info("  {}: {}/{} rows", label, done, total);
        }
    }
}
