package com.ahdyahmed.pymk.stream.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventContractTest {
    private final EventContract contract = new EventContract();
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void publishedV1SchemasAreFrozen() throws Exception {
        // Normalize checkout line endings only. A wire-contract change requires a new version,
        // not silently updating the published schema and its compatibility fixtures.
        for (var entry : Map.of(
                "member-events.v1.schema.json", "320067fe9a4cc4df6afce0e0a7f4ba6600486ccfb2b82fa67f9fea9335488692",
                "connection-events.v1.schema.json", "449fdfbc54ead310884acfdaeace008eb44c35fdba56ad9e15b1efc930087e7f").entrySet()) {
            try (var input = EventContract.class.getResourceAsStream("/contracts/" + entry.getKey())) {
                assertThat(input).isNotNull();
                byte[] normalized = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
                assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(normalized)))
                        .as(entry.getKey()).isEqualTo(entry.getValue());
            }
        }
    }

    @Test
    void frozenV1FixturesRoundTripWithCanonicalKeys() throws Exception {
        var member = member();
        var connection = connection();
        assertThat(member.key()).isEqualTo("42");
        assertThat(connection.key()).isEqualTo("42:100");
        assertThat(connection.eventType()).isNotEqualTo(StreamEventType.INVITE_ACCEPTED);
        for (var event : new EventEnvelope[]{member, connection}) {
            assertThat(contract.decode(event.topic(), event.key(), contract.encode(event))).isEqualTo(event);
        }
    }

    @ParameterizedTest
    @EnumSource(value = StreamEventType.class, names = "CONNECTION_CREATED", mode = EnumSource.Mode.EXCLUDE)
    void allExistingMemberEventKindsRetainDirectionalIdentity(StreamEventType type) throws Exception {
        ObjectNode node = memberNode();
        node.put("eventType", type.name());
        node.put("subjectMemberId", 100);
        node.put("targetMemberId", 42);
        var event = contract.decode(KafkaTopics.MEMBER_EVENTS, "100", node.toString());
        assertThat(event.eventType()).isEqualTo(type);
        assertThat(event.targetMemberId()).isEqualTo(42);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMembers")
    void rejectsSchemaAndSemanticViolations(String description, Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode node = memberNode();
        mutation.accept(node);
        assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.MEMBER_EVENTS, "42", node.toString()));
    }

    static Stream<Arguments> invalidMembers() {
        return Stream.of(
                invalid("unknown schema", n -> n.put("schemaVersion", 2)),
                invalid("fractional schema representation", n -> n.put("schemaVersion", 1.0)),
                invalid("unknown event kind", n -> n.put("eventType", "FUTURE_EVENT")),
                invalid("wrong topic kind", n -> n.put("eventType", "CONNECTION_CREATED")),
                invalid("extra field", n -> n.put("secret", "must not leak")),
                invalid("missing receipt time", n -> n.remove("ingestedAt")),
                invalid("missing correlation", n -> n.remove("correlationId")),
                invalid("malformed UUID", n -> n.put("eventId", "1-1-1-1-1")),
                invalid("noncanonical UUID", n -> n.put("eventId", "1FDF16FA-E0B9-4D38-8C97-182B247C4EAE")),
                invalid("nonexistent date", n -> n.put("occurredAt", "2026-02-30T12:00:00Z")),
                invalid("missing time zone", n -> n.put("occurredAt", "2026-01-01T12:00:00")),
                invalid("non-UTC time zone", n -> n.put("occurredAt", "2026-01-01T12:00:00+02:00")),
                invalid("too much timestamp precision", n -> n.put("occurredAt", "2026-01-01T12:00:00.1234567891Z")),
                invalid("self event", n -> n.put("targetMemberId", 42)),
                invalid("zero ID", n -> n.put("targetMemberId", 0)),
                invalid("negative ID", n -> n.put("targetMemberId", -1)),
                invalid("string ID", n -> n.put("targetMemberId", "100")),
                invalid("fractional ID", n -> n.put("targetMemberId", 100.5)),
                invalid("float ID representation", n -> n.put("targetMemberId", 100.0)),
                invalid("int64 overflow", n -> n.put("targetMemberId", new java.math.BigInteger("9223372036854775808"))),
                invalid("null payload", n -> n.putNull("payload")),
                invalid("missing payload", n -> n.remove("payload")),
                invalid("unexpected member payload", n -> ((ObjectNode) n.get("payload")).put("aggregateSequence", 1)));
    }

    private static Arguments invalid(String label, Consumer<ObjectNode> mutation) {
        return Arguments.of(label, mutation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{} {}"})
    void rejectsMalformedOrNonObjectJson(String json) {
        assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.MEMBER_EVENTS, "42", json));
    }

    @Test
    void rejectsDuplicateJsonFieldsTrailingDataOversizeTombstonesAndUnknownTopics() throws Exception {
        String json = fixture("member-event.json");
        for (String invalid : new String[]{null, json + " {}", " ".repeat(EventContract.MAX_BYTES) + json,
                json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1")}) {
            assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.MEMBER_EVENTS, "42", invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> contract.decode("pymk.member-events.v2", "42", json));
        for (String key : new String[]{null, "100", "042", "42:100"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.MEMBER_EVENTS, key, json));
        }
    }

    @Test
    void connectionRequiresCanonicalPairAndPositiveSequenceAndCorrectTopic() throws Exception {
        ObjectNode original = (ObjectNode) mapper.readTree(fixture("connection-event.json"));
        for (long sequence : new long[]{0, -1}) {
            ObjectNode invalid = original.deepCopy();
            ((ObjectNode) invalid.get("payload")).put("aggregateSequence", sequence);
            assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", invalid.toString()));
        }
        ObjectNode reversed = original.deepCopy().put("subjectMemberId", 100).put("targetMemberId", 42);
        assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", reversed.toString()));
        original.set("payload", mapper.createObjectNode());
        assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", original.toString()));
        assertThatIllegalArgumentException().isThrownBy(() -> contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", fixture("member-event.json")));
    }

    @Test
    void identicalProducerContentKeepsIdentityAcrossReceiptTimesAndJsonFormatting() throws Exception {
        var stored = member();
        ObjectNode retry = memberNode();
        retry.put("ingestedAt", "2026-01-02T12:00:00Z");
        retry.put("occurredAt", "2026-01-01T12:00:00.000Z");
        var incoming = contract.decode(KafkaTopics.MEMBER_EVENTS, "42", retry.toPrettyString());
        assertThat(contract.compareIdentity(stored, incoming)).isEqualTo(EventContract.IdentityMatch.DUPLICATE);
        assertThat(contract.contentFingerprint(incoming)).matches("[0-9a-f]{64}");
        assertThat(stored.ingestedAt()).isEqualTo(Instant.parse("2026-01-01T12:01:00Z"));
    }

    @Test
    void sameIdDifferentContentConflictsAndDifferentIdIsDistinct() throws Exception {
        var stored = member();
        for (Consumer<ObjectNode> mutation : java.util.List.<Consumer<ObjectNode>>of(
                n -> n.put("targetMemberId", 101), n -> n.put("eventType", "INVITE_SENT"),
                n -> n.put("occurredAt", "2026-01-01T12:00:01Z"),
                n -> n.put("correlationId", UUID.randomUUID().toString()))) {
            ObjectNode changed = memberNode();
            mutation.accept(changed);
            assertThat(contract.compareIdentity(stored, contract.decode(KafkaTopics.MEMBER_EVENTS, "42", changed.toString())))
                    .isEqualTo(EventContract.IdentityMatch.CONFLICT);
        }
        ObjectNode distinct = memberNode().put("eventId", UUID.randomUUID().toString());
        assertThat(contract.compareIdentity(stored, contract.decode(KafkaTopics.MEMBER_EVENTS, "42", distinct.toString())))
                .isEqualTo(EventContract.IdentityMatch.DISTINCT);
        ObjectNode connection = (ObjectNode) mapper.readTree(fixture("connection-event.json"));
        connection.put("eventId", stored.eventId().toString());
        assertThat(contract.compareIdentity(stored, contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", connection.toString())))
                .isEqualTo(EventContract.IdentityMatch.CONFLICT);
        var connectionBefore = contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", connection.toString());
        ((ObjectNode) connection.get("payload")).put("aggregateSequence", 2);
        assertThat(contract.compareIdentity(connectionBefore, contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", connection.toString())))
                .isEqualTo(EventContract.IdentityMatch.CONFLICT);
    }

    @Test
    void envelopeDefensivelyCopiesPayloadAndAcceptsFullInt64Ids() throws Exception {
        var event = connection();
        var payload = new HashMap<>(event.payload());
        var copy = new EventEnvelope(1, event.eventId(), event.eventType(), event.occurredAt(), event.ingestedAt(),
                event.subjectMemberId(), Long.MAX_VALUE, event.correlationId(), payload);
        payload.put("aggregateSequence", 99L);
        assertThat(copy.payload()).isEqualTo(Map.of("aggregateSequence", 1L));
        assertThat(contract.decode(copy.topic(), copy.key(), contract.encode(copy))).isEqualTo(copy);
        assertThatIllegalArgumentException().isThrownBy(() -> new EventEnvelope(2, event.eventId(), event.eventType(),
                event.occurredAt(), event.ingestedAt(), 42, 100, event.correlationId(), Map.of("aggregateSequence", 1L)));
    }

    private ObjectNode memberNode() throws IOException {
        return (ObjectNode) mapper.readTree(fixture("member-event.json"));
    }

    private EventEnvelope member() throws IOException {
        return contract.decode(KafkaTopics.MEMBER_EVENTS, "42", fixture("member-event.json"));
    }

    private EventEnvelope connection() throws IOException {
        return contract.decode(KafkaTopics.CONNECTION_EVENTS, "42:100", fixture("connection-event.json"));
    }

    static String fixture(String name) throws IOException {
        try (var input = EventContractTest.class.getResourceAsStream("/contracts/v1/" + name)) {
            if (input == null) throw new IOException("Missing frozen v1 fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
