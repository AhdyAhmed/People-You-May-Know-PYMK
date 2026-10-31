package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.KafkaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Fixed-cardinality process metrics. No payload, member ID, event ID or exception-message labels. */
@Component
public class EventMetrics {
    private final MeterRegistry registry;
    public EventMetrics(MeterRegistry registry) { this.registry = registry; }

    public void send(long started, boolean acknowledged) {
        registry.timer("pymk.events.outbox.send", "outcome", acknowledged ? "acknowledged" : "failed")
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    public void process(String group, long started, boolean committed) {
        registry.timer("pymk.events.consumer.process", "group", group(group), "outcome", committed ? "committed" : "failed")
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    public void retry(String group) {
        registry.counter("pymk.events.consumer.retries", "group", group(group)).increment();
    }
    public void deadLetter(String group, boolean acknowledged) {
        registry.counter("pymk.events.consumer.dead.letters", "group", group(group),
                "outcome", acknowledged ? "acknowledged" : "failed").increment();
    }
    static String group(String group) {
        return KafkaTopics.FEATURE_PROJECTION_GROUP.equals(group) || KafkaTopics.CACHE_INVALIDATION_GROUP.equals(group)
                ? group : "other";
    }
}
