package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.KafkaTopics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Cached gauges: scrapes perform no SQL or broker calls. Monitoring never gates serving health. */
@Component
@ConditionalOnProperty(name = "pymk.events.monitoring.enabled", havingValue = "true")
public class EventMonitor {
    static final List<String> GROUPS = List.of(KafkaTopics.FEATURE_PROJECTION_GROUP, KafkaTopics.CACHE_INVALIDATION_GROUP);
    private final JdbcTemplate jdbc;
    private final Admin admin;
    private final long pollMillis;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "pymk-event-monitor"); thread.setDaemon(true); return thread;
    });
    private volatile Map<String, Double> database = Map.of();
    private volatile Map<String, Double> kafka = Map.of();
    private volatile double databaseUp, kafkaUp;
    private volatile double databaseSuccess = Double.NaN, kafkaSuccess = Double.NaN;

    public EventMonitor(DataSource source, MeterRegistry registry,
            @Value("${pymk.events.monitoring.poll-ms:10000}") long pollMillis,
            @Value("${pymk.events.monitoring.kafka-enabled:${pymk.events.consumers.enabled:false}}") boolean kafkaEnabled,
            @Value("${pymk.events.consumers.bootstrap-servers:localhost:9092}") String bootstrap) {
        if (pollMillis < 1000) throw new IllegalArgumentException("Event monitoring poll interval must be >= 1000ms");
        this.pollMillis = pollMillis;
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(2);
        admin = kafkaEnabled ? Admin.create(Map.of("bootstrap.servers", bootstrap,
                "request.timeout.ms", 2000, "default.api.timeout.ms", 2000)) : null;
        Gauge.builder("pymk.events.monitor.up", this, m -> m.databaseUp).tag("source", "database").register(registry);
        Gauge.builder("pymk.events.monitor.last.success", this, m -> m.databaseSuccess).tag("source", "database").register(registry);
        databaseGauge(registry, "outbox.pending", "", "outbox.pending");
        databaseGauge(registry, "outbox.oldest.age", "", "outbox.age");
        for (String group : GROUPS) {
            databaseGauge(registry, "projection.pending", group, group + ".pending");
            databaseGauge(registry, "projection.oldest.age", group, group + ".age");
        }
        if (admin != null) {
            Gauge.builder("pymk.events.monitor.up", this, m -> m.kafkaUp).tag("source", "kafka").register(registry);
            Gauge.builder("pymk.events.monitor.last.success", this, m -> m.kafkaSuccess).tag("source", "kafka").register(registry);
            for (String group : GROUPS) {
                Gauge.builder("pymk.events.consumer.lag", this, m -> m.kafka.getOrDefault(group + ".lag", Double.NaN))
                        .tag("group", group).register(registry);
                Gauge.builder("pymk.events.consumer.offset.out.of.range", this, m -> m.kafka.getOrDefault(group + ".invalid", Double.NaN))
                        .tag("group", group).register(registry);
            }
        }
    }
    private void databaseGauge(MeterRegistry registry, String name, String group, String key) {
        var builder = Gauge.builder("pymk.events." + name, this, m -> m.database.getOrDefault(key, Double.NaN));
        if (!group.isEmpty()) builder.tag("group", group);
        builder.register(registry);
    }
    @PostConstruct void start() { executor.scheduleWithFixedDelay(this::poll, pollMillis, pollMillis, TimeUnit.MILLISECONDS); }
    @PreDestroy void close() {
        executor.shutdownNow();
        if (admin != null) admin.close(Duration.ofSeconds(2));
    }
    public synchronized void poll() {
        try {
            var values = new HashMap<String, Double>();
            pending(values, "outbox", "SELECT ingested_at FROM event_outbox WHERE published_at IS NULL");
            pending(values, KafkaTopics.FEATURE_PROJECTION_GROUP, """
                    SELECT o.ingested_at FROM event_outbox o
                    WHERE o.envelope::jsonb->>'eventType' IN ('PROFILE_VIEW','INVITE_SENT','INVITE_ACCEPTED')
                    AND NOT EXISTS (SELECT 1 FROM live_member_event e JOIN live_projection_active a
                        ON e.generation = a.generation WHERE e.event_id = o.event_id)
                    """);
            pending(values, KafkaTopics.CACHE_INVALIDATION_GROUP, """
                    SELECT o.ingested_at FROM event_outbox o
                    WHERE o.envelope::jsonb->>'eventType' = 'CONNECTION_CREATED'
                    AND NOT EXISTS (SELECT 1 FROM live_connection_state s
                        WHERE s.first_member_id = (o.envelope::jsonb->>'subjectMemberId')::bigint
                        AND s.second_member_id = (o.envelope::jsonb->>'targetMemberId')::bigint
                        AND s.aggregate_sequence >= (o.envelope::jsonb->'payload'->>'aggregateSequence')::bigint)
                    """);
            database = Map.copyOf(values); databaseSuccess = System.currentTimeMillis() / 1000.0; databaseUp = 1;
        } catch (RuntimeException unavailable) {
            database = Map.of(); databaseUp = 0;
        }
        if (admin != null) pollKafka();
    }
    private void pending(Map<String, Double> values, String key, String source) {
        jdbc.query("SELECT count(*), COALESCE(GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - min(ingested_at)))), 0) FROM ("
                + source + ") pending", rs -> {
            rs.next(); values.put(key + ".pending", rs.getDouble(1)); values.put(key + ".age", rs.getDouble(2)); return null;
        });
    }
    private void pollKafka() {
        try {
            var descriptions = admin.describeTopics(List.of(KafkaTopics.MEMBER_EVENTS, KafkaTopics.CONNECTION_EVENTS))
                    .allTopicNames().get(3, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>(), earliest = new HashMap<>();
            descriptions.forEach((topic, description) -> description.partitions().forEach(partition -> {
                var tp = new TopicPartition(topic, partition.partition());
                latest.put(tp, OffsetSpec.latest()); earliest.put(tp, OffsetSpec.earliest());
            }));
            // Read commits before log ends so normal concurrent publishing cannot look like truncation.
            var groupOffsets = new HashMap<String, Map<TopicPartition, OffsetAndMetadata>>();
            for (String group : GROUPS) {
                try {
                    groupOffsets.put(group, admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException)
                        groupOffsets.put(group, Map.of());
                    else throw failure;
                }
            }
            var starts = admin.listOffsets(earliest).all().get(3, TimeUnit.SECONDS);
            var ends = admin.listOffsets(latest).all().get(3, TimeUnit.SECONDS);
            var values = new HashMap<String, Double>();
            for (String group : GROUPS) {
                var committed = groupOffsets.get(group);
                long lag = 0, invalid = 0;
                for (var tp : latest.keySet()) {
                    long start = starts.get(tp).offset(), end = ends.get(tp).offset();
                    OffsetAndMetadata offset = committed.get(tp);
                    long position = offset == null ? start : offset.offset();
                    lag += retainedLag(start, end, position);
                    if (position < start || position > end) invalid++;
                }
                values.put(group + ".lag", (double) lag); values.put(group + ".invalid", (double) invalid);
            }
            kafka = Map.copyOf(values); kafkaSuccess = System.currentTimeMillis() / 1000.0; kafkaUp = 1;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            kafka = Map.of(); kafkaUp = 0;
        }
    }
    static long retainedLag(long start, long end, long committed) {
        return Math.max(0, end - Math.max(start, committed));
    }
}
