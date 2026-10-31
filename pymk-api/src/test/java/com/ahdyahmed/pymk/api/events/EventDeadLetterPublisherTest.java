package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import com.ahdyahmed.pymk.stream.contract.KafkaTopics;

class EventDeadLetterPublisherTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void retainsMalformedBytesAndSafeReasonWithoutExposingExceptionText() {
        KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var input = new ConsumerRecord<byte[], byte[]>(KafkaTopics.MEMBER_EVENTS, 2, 99,
                new byte[]{1}, new byte[]{(byte) 0xff});
        input.headers().add("source-trace", new byte[]{2});
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new EventDeadLetterPublisher(template, new EventMetrics(registry)).recover("test-consumer", input,
                new IllegalStateException("private payload must not be copied"));
        ArgumentCaptor<ProducerRecord<byte[], byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        var output = captor.getValue();
        assertThat(output.topic()).isEqualTo(KafkaTopics.MEMBER_EVENTS_DLT);
        assertThat(output.partition()).isEqualTo(2);
        assertThat(output.key()).isEqualTo(input.key());
        assertThat(output.value()).isEqualTo(input.value());
        assertThat(output.headers().lastHeader("source-trace").value()).containsExactly(2);
        assertThat(new String(output.headers().lastHeader("pymk-dlt-reason").value(), StandardCharsets.UTF_8))
                .isEqualTo("PROCESSING_FAILED");
        assertThat(registry.get("pymk.events.consumer.dead.letters").tag("outcome", "acknowledged").counter().count()).isEqualTo(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void failedBrokerAcknowledgementCannotBeReportedAsRecovered() {
        KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        var input = new ConsumerRecord<byte[], byte[]>(KafkaTopics.CONNECTION_EVENTS, 0, 4, null, null);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        assertThatThrownBy(() -> new EventDeadLetterPublisher(template, new EventMetrics(registry)).recover("test-consumer", input,
                new IllegalArgumentException())).hasMessage("DLT publication unacknowledged");
        assertThat(registry.get("pymk.events.consumer.dead.letters").tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(registry.find("pymk.events.consumer.dead.letters").tag("outcome", "acknowledged").counter()).isNull();
    }
}
