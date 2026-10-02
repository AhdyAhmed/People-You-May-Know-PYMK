package com.ahdyahmed.pymk.datagen.load;

import com.ahdyahmed.pymk.datagen.generate.ConnectionTimelineGenerator.TimestampedEdge;
import com.ahdyahmed.pymk.datagen.generate.EventGenerator.EventRecord;
import com.ahdyahmed.pymk.datagen.generate.MemberFactory.MemberRecord;
import com.ahdyahmed.pymk.domain.vector.VectorLiterals;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
        executeMappedBatches(
                "INSERT INTO members (id, full_name, headline, company, school, geo_region, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                members,
                m -> new Object[] {
                    m.id(), m.fullName(), m.headline(), m.company(), m.school(),
                    m.geoRegion(), Timestamp.from(m.createdAt())
                },
                "members");
    }

    /** Writes both directed rows per undirected edge, matching the symmetric edge-list schema. */
    public void insertConnections(List<TimestampedEdge> edges) {
        String sql = "INSERT INTO connections (member_id, connected_member_id, connected_at) VALUES (?,?,?)";
        int total = edges.size() * 2;
        int done = 0;
        List<Object[]> rows = new ArrayList<>(BATCH_SIZE);
        for (TimestampedEdge te : edges) {
            Timestamp ts = Timestamp.from(te.connectedAt());
            rows.add(new Object[] {te.edge().memberA(), te.edge().memberB(), ts});
            rows.add(new Object[] {te.edge().memberB(), te.edge().memberA(), ts});
            if (rows.size() >= BATCH_SIZE) {
                done += flush(sql, rows);
                log.info("  connections: {}/{} rows", done, total);
            }
        }
        if (!rows.isEmpty()) {
            done += flush(sql, rows);
            log.info("  connections: {}/{} rows", done, total);
        }
    }

    public void insertEvents(List<EventRecord> events) {
        executeMappedBatches(
                "INSERT INTO member_events (actor_member_id, target_member_id, type, occurred_at) VALUES (?,?,?,?)",
                events,
                e -> new Object[] {
                    e.actorMemberId(), e.targetMemberId(), e.type().name(), Timestamp.from(e.occurredAt())
                },
                "member_events");
    }

    /**
     * Bulk-writes placeholder embeddings. Binds each vector as a plain text
     * literal and casts server-side with {@code CAST(? AS vector)} - see
     * {@link VectorLiterals} for why that avoids any pgvector-specific JDBC
     * type registration.
     */
    public void insertEmbeddings(List<MemberEmbeddingRecord> embeddings) {
        executeMappedBatches(
                "INSERT INTO member_embeddings (member_id, embedding, updated_at) VALUES (?, CAST(? AS vector), ?)",
                embeddings,
                e -> new Object[] {
                    e.memberId(), VectorLiterals.toLiteral(e.embedding()), Timestamp.from(e.updatedAt())
                },
                "member_embeddings");
    }

    public record MemberEmbeddingRecord(long memberId, float[] embedding, Instant updatedAt) {
    }

    private <T> void executeMappedBatches(
            String sql, List<T> source, Function<T, Object[]> rowMapper, String label) {
        int total = source.size();
        int done = 0;
        for (int start = 0; start < total; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, total);
            List<Object[]> rows = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                rows.add(rowMapper.apply(source.get(i)));
            }
            jdbc.batchUpdate(sql, rows);
            done = end;
            log.info("  {}: {}/{} rows", label, done, total);
        }
    }

    private int flush(String sql, List<Object[]> rows) {
        int size = rows.size();
        jdbc.batchUpdate(sql, rows);
        rows.clear();
        return size;
    }
}
