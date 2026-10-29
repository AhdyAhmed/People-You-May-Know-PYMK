package com.ahdyahmed.pymk.api.events;

import com.ahdyahmed.pymk.stream.contract.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.KafkaTemplate;

/** Success means an acknowledged durable DLT send, never merely enqueueing a producer future. */
public class EventDeadLetterPublisher {
    public static final String PREFIX = "pymk-dlt-";
    private final KafkaTemplate<byte[], byte[]> template;
    public EventDeadLetterPublisher(KafkaTemplate<byte[], byte[]> template) { this.template = template; }

    public void recover(String group, ConsumerRecord<byte[], byte[]> record, Exception failure) {
        var headers = new RecordHeaders();
        for (var header : record.headers()) {
            if (!header.key().startsWith(PREFIX)) headers.add(header);
        }
        add(headers, "version", "1");
        add(headers, "consumer", group);
        add(headers, "topic", record.topic());
        add(headers, "partition", Integer.toString(record.partition()));
        add(headers, "offset", Long.toString(record.offset()));
        add(headers, "timestamp", Long.toString(record.timestamp()));
        add(headers, "timestamp-type", record.timestampType().name());
        String reason = "PROCESSING_FAILED";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConsumerEventFailure invalid) { reason = invalid.reason().name(); break; }
        }
        add(headers, "reason", reason);
        var deadLetter = new ProducerRecord<byte[], byte[]>(KafkaTopics.deadLetterTopic(record.topic()),
                record.partition(), null, record.key(), record.value(), headers);
        try {
            template.send(deadLetter).get(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLT publication interrupted");
        } catch (Exception unavailable) {
            // Throwing prevents the error handler from advancing the source offset.
            throw new IllegalStateException("DLT publication unacknowledged");
        }
    }
    private static void add(RecordHeaders headers, String name, String value) {
        headers.add(PREFIX + name, value.getBytes(StandardCharsets.UTF_8));
    }
}
