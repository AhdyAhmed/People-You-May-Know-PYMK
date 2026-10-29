package com.ahdyahmed.pymk.stream.contract;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class DeadLetterReplayTest {
    ConsumerRecord<byte[], byte[]> dlt() throws Exception {
        var record = new ConsumerRecord<byte[], byte[]>(KafkaTopics.MEMBER_EVENTS_DLT, 1, 42,
                "42".getBytes(StandardCharsets.UTF_8),
                EventContractTest.fixture("member-event.json").getBytes(StandardCharsets.UTF_8));
        record.headers().add("pymk-dlt-version", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("pymk-dlt-topic", KafkaTopics.MEMBER_EVENTS.getBytes(StandardCharsets.UTF_8));
        record.headers().add("trace", new byte[]{1, 2});
        return record;
    }

    @Test void preservesExactProducerBytesAndIdentityWithoutRecoveryHeaderAmplification() throws Exception {
        var original = dlt();
        var replay = DeadLetterReplay.sourceRecord(original);
        assertThat(replay.topic()).isEqualTo(KafkaTopics.MEMBER_EVENTS);
        assertThat(replay.key()).isEqualTo(original.key());
        assertThat(replay.value()).isEqualTo(original.value());
        assertThat(replay.headers().toArray()).hasSize(1);
        assertThat(replay.headers().lastHeader("trace").value()).containsExactly(1, 2);
    }

    @Test void rejectsWrongTopicVersionAndMissingMetadata() throws Exception {
        var record = dlt();
        record.headers().remove("pymk-dlt-version");
        assertThatThrownBy(() -> DeadLetterReplay.sourceRecord(record)).isInstanceOf(IllegalArgumentException.class);
        record.headers().add("pymk-dlt-version", "2".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> DeadLetterReplay.sourceRecord(record)).isInstanceOf(IllegalArgumentException.class);
        var source = new ConsumerRecord<byte[], byte[]>(KafkaTopics.MEMBER_EVENTS, 0, 0, record.key(), record.value());
        assertThatThrownBy(() -> DeadLetterReplay.sourceRecord(source)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void malformedPoisonCannotBeSilentlyRepairedByReplay() throws Exception {
        var original = dlt();
        var bad = new ConsumerRecord<byte[], byte[]>(original.topic(), 0, 0, original.key(), new byte[]{(byte) 0xff});
        for (var header : original.headers()) bad.headers().add(header);
        assertThatThrownBy(() -> DeadLetterReplay.sourceRecord(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
