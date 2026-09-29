package com.ahdyahmed.pymk.datagen.report;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Post-load sanity check (Day 4, PYMK_ROADMAP.md: "Load it into Postgres;
 * sanity-check counts and distribution."). Queries the tables that were
 * just written and logs a human-readable report - counts, degree
 * distribution, and the top companies/event types - rather than asserting
 * anything, since "does this look realistic" is a judgment call for whoever
 * runs the generator.
 */
@Component
public class SummaryReporter {

    private static final Logger log = LoggerFactory.getLogger(SummaryReporter.class);

    private final JdbcTemplate jdbc;

    public SummaryReporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void report() {
        long memberCount = count("members");
        long connectionCount = count("connections");
        long eventCount = count("member_events");

        StringBuilder sb = new StringBuilder();
        sb.append("\n================ PYMK synthetic dataset summary ================\n");
        sb.append("members:         ").append(memberCount).append('\n');
        sb.append("connections:     ").append(connectionCount)
                .append(" directed rows (").append(connectionCount / 2).append(" undirected edges)\n");
        sb.append("member_events:   ").append(eventCount).append('\n');
        sb.append('\n');
        sb.append(degreeDistribution());
        sb.append('\n');
        sb.append(topCompanies());
        sb.append('\n');
        sb.append(eventTypeCounts());
        sb.append("==================================================================");

        log.info(sb.toString());
    }

    private long count(String table) {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return result == null ? 0 : result;
    }

    private String degreeDistribution() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    MIN(deg) AS min_degree,
                    MAX(deg) AS max_degree,
                    ROUND(AVG(deg), 2) AS avg_degree,
                    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY deg) AS p50,
                    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY deg) AS p95,
                    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY deg) AS p99
                FROM (
                    SELECT member_id, COUNT(*) AS deg
                    FROM connections
                    GROUP BY member_id
                ) degrees
                """);
        return "degree distribution: min=%s  p50=%s  avg=%s  p95=%s  p99=%s  max=%s%n"
                .formatted(row.get("min_degree"), row.get("p50"), row.get("avg_degree"),
                        row.get("p95"), row.get("p99"), row.get("max_degree"));
    }

    private String topCompanies() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT company, COUNT(*) AS members
                FROM members
                GROUP BY company
                ORDER BY members DESC
                LIMIT 5
                """);
        StringBuilder sb = new StringBuilder("top 5 companies by headcount:\n");
        for (Map<String, Object> row : rows) {
            sb.append("  ").append(row.get("company")).append(": ").append(row.get("members")).append('\n');
        }
        return sb.toString();
    }

    private String eventTypeCounts() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT type, COUNT(*) AS events
                FROM member_events
                GROUP BY type
                ORDER BY events DESC
                """);
        StringBuilder sb = new StringBuilder("event type counts:\n");
        for (Map<String, Object> row : rows) {
            sb.append("  ").append(row.get("type")).append(": ").append(row.get("events")).append('\n');
        }
        return sb.toString();
    }
}
