package com.ahdyahmed.pymk.stream.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable v1 event. Ingestion time is assigned once by the future durable receipt. */
public record EventEnvelope(
        int schemaVersion, UUID eventId, StreamEventType eventType,
        Instant occurredAt, Instant ingestedAt, long subjectMemberId, long targetMemberId,
        UUID correlationId, Map<String, Long> payload) {

    public EventEnvelope {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported event schema version");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        if (subjectMemberId <= 0 || targetMemberId <= 0 || subjectMemberId == targetMemberId) {
            throw new IllegalArgumentException("Events require two distinct positive member IDs");
        }
        if (eventType == StreamEventType.CONNECTION_CREATED) {
            if (subjectMemberId >= targetMemberId) {
                throw new IllegalArgumentException("Connection endpoints must be canonical: subject < target");
            }
            if (!payload.keySet().equals(java.util.Set.of("aggregateSequence"))
                    || payload.get("aggregateSequence") <= 0) {
                throw new IllegalArgumentException("Connection payload requires a positive aggregateSequence only");
            }
        } else if (!payload.isEmpty()) {
            throw new IllegalArgumentException("Member v1 events have an empty payload");
        }
    }

    public String topic() {
        return eventType.topic();
    }

    public String key() {
        return eventType == StreamEventType.CONNECTION_CREATED
                ? subjectMemberId + ":" + targetMemberId : Long.toString(subjectMemberId);
    }
}
