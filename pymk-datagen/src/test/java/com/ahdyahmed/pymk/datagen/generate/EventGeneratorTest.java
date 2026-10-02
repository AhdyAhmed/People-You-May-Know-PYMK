package com.ahdyahmed.pymk.datagen.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.datagen.generate.EventGenerator.EventRecord;
import com.ahdyahmed.pymk.domain.entity.EventType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class EventGeneratorTest {

    @Test
    void additionalAttemptsProduceImplicitSignalsAndNeverFutureEvents() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        Map<Long, Instant> memberCreatedAt = LongStream.rangeClosed(1, 100)
                .boxed()
                .collect(Collectors.toMap(id -> id, id -> Instant.parse("2020-01-01T00:00:00Z")));

        List<EventRecord> events = new EventGenerator(new Random(42))
                .generate(100, List.of(), 2_000, now, memberCreatedAt);

        assertThat(events)
                .anyMatch(event -> event.type() == EventType.PROFILE_VIEW)
                .anyMatch(event -> event.type() == EventType.SEARCH_APPEARANCE)
                .allMatch(event -> !event.occurredAt().isAfter(now));
    }
}
