package com.ahdyahmed.pymk.stream.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Local schema + semantic validation only. Does not connect to Kafka or hold deduplication state. */
public final class EventContract {
    public static final int MAX_BYTES = 16_384;
    private final JsonMapper mapper;
    private final Map<String, JsonSchema> schemas;

    public EventContract() {
        mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(MAX_BYTES).maxNumberLength(20).build());
        schemas = Map.of(
                KafkaTopics.MEMBER_EVENTS, load("member-events.v1.schema.json"),
                KafkaTopics.CONNECTION_EVENTS, load("connection-events.v1.schema.json"));
    }

    public EventEnvelope decode(String topic, String key, String json) {
        JsonSchema schema = schemas.get(Objects.requireNonNull(topic, "topic"));
        if (schema == null) throw new IllegalArgumentException("Unknown event topic/version");
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Event must be non-null and at most " + MAX_BYTES + " UTF-8 bytes");
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !schema.validate(node).isEmpty()) {
                // Avoid copying member data into validation logs/errors.
                throw new IllegalArgumentException("Event does not match the topic's v1 JSON Schema");
            }
            long subject = positiveLong(node.get("subjectMemberId"));
            long target = positiveLong(node.get("targetMemberId"));
            positiveLong(node.get("schemaVersion"));
            StreamEventType type = StreamEventType.valueOf(node.get("eventType").textValue());
            Map<String, Long> payload = type == StreamEventType.CONNECTION_CREATED
                    ? Map.of("aggregateSequence", positiveLong(node.get("payload").get("aggregateSequence")))
                    : Map.of();
            EventEnvelope event = new EventEnvelope(1, UUID.fromString(node.get("eventId").textValue()), type,
                    Instant.parse(node.get("occurredAt").textValue()), Instant.parse(node.get("ingestedAt").textValue()),
                    subject, target, UUID.fromString(node.get("correlationId").textValue()), payload);
            if (!event.key().equals(key)) throw new IllegalArgumentException("Kafka key does not match the event");
            return event;
        } catch (JsonProcessingException | DateTimeParseException exception) {
            throw new IllegalArgumentException("Malformed event JSON or timestamp");
        }
    }

    public String encode(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        String json = node(event).toString();
        // The same contract governs both production and consumption, including timestamp bounds.
        decode(event.topic(), event.key(), json);
        return json;
    }

    /** Stable producer content, excluding the server-assigned receipt time; not a raw JSON hash. */
    public String contentFingerprint(EventEnvelope event) {
        encode(event);
        ObjectNode canonical = node(event);
        canonical.remove("ingestedAt");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Future durable receipt/consumer stores must enforce this comparison transactionally. */
    public IdentityMatch compareIdentity(EventEnvelope stored, EventEnvelope incoming) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(incoming, "incoming");
        if (!stored.eventId().equals(incoming.eventId())) return IdentityMatch.DISTINCT;
        return contentFingerprint(stored).equals(contentFingerprint(incoming))
                ? IdentityMatch.DUPLICATE : IdentityMatch.CONFLICT;
    }

    public enum IdentityMatch { DISTINCT, DUPLICATE, CONFLICT }

    private static long positiveLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Member IDs and aggregate sequences must be positive int64 integers");
        }
        return value.longValue();
    }

    private ObjectNode node(EventEnvelope event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", event.schemaVersion());
        node.put("eventId", event.eventId().toString());
        node.put("eventType", event.eventType().name());
        node.put("occurredAt", event.occurredAt().toString());
        node.put("ingestedAt", event.ingestedAt().toString());
        node.put("subjectMemberId", event.subjectMemberId());
        node.put("targetMemberId", event.targetMemberId());
        node.put("correlationId", event.correlationId().toString());
        ObjectNode payload = node.putObject("payload");
        event.payload().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> payload.put(entry.getKey(), entry.getValue()));
        return node;
    }

    private JsonSchema load(String name) {
        try (var input = EventContract.class.getResourceAsStream("/contracts/" + name)) {
            if (input == null) throw new IllegalStateException("Missing event schema " + name);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(mapper.readTree(input), SchemaValidatorsConfig.builder()
                            .formatAssertionsEnabled(true).build());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load event schema " + name, exception);
        }
    }
}
