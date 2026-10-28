package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Strict, bounded JSON ingress. ingestedAt is server owned and forbidden in requests. */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {
    private final EventIngestionService ingestion;
    private final Clock clock;
    private final EventContract contract = new EventContract();
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public EventController(EventIngestionService ingestion, Clock clock) {
        this.ingestion = ingestion;
        this.clock = clock;
        mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(EventContract.MAX_BYTES).maxNumberLength(20).build());
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @io.swagger.v3.oas.annotations.Operation(summary = "Durably accept a v1 member event",
            description = "Commits member_events and an outbox receipt together. Kafka delivery is asynchronous. "
                    + "Reuse the same eventId and content for retries; ingestedAt must not be supplied.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = EventRequest.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Durably accepted, including identical retries")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Event identity/content conflict")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Backlog full; retry later with the same identity")
    public ResponseEntity<EventIngestionService.Receipt> ingest(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(EventContract.MAX_BYTES + 1);
        if (bytes.length > EventContract.MAX_BYTES) throw new EventFailure(413, "Event request is too large");
        EventEnvelope event;
        try {
            var tree = mapper.readTree(bytes);
            if (!(tree instanceof ObjectNode node) || node.has("ingestedAt"))
                throw new IllegalArgumentException();
            node.put("ingestedAt", clock.instant().toString());
            var subject = node.get("subjectMemberId");
            if (subject == null) throw new IllegalArgumentException();
            event = contract.decode(KafkaTopics.MEMBER_EVENTS, subject.asText(), node.toString());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new EventFailure(400, "Invalid v1 member event; ingestedAt is server assigned");
        }
        return ResponseEntity.accepted().body(ingestion.ingest(event));
    }

    /** Documentation shape only; ingress is parsed strictly without Jackson scalar coercions. */
    public record EventRequest(int schemaVersion, java.util.UUID eventId,
            @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {
                    "PROFILE_VIEW", "SEARCH_APPEARANCE", "INVITE_SENT", "INVITE_ACCEPTED", "INVITE_IGNORED"})
            String eventType, java.time.Instant occurredAt, long subjectMemberId, long targetMemberId,
            java.util.UUID correlationId, java.util.Map<String, Long> payload) {}
}
