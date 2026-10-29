package com.ahdyahmed.pymk.stream.contract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Byte deserialization preserves even malformed JSON/UTF-8 for dead-letter recovery. */
public final class EventRecordDecoder {
    private final EventContract contract = new EventContract();
    public EventEnvelope decode(ConsumerRecord<byte[], byte[]> record) {
        if (record.value() == null || record.value().length > EventContract.MAX_BYTES
                || record.key() == null || record.key().length > 128)
            throw new IllegalArgumentException("Invalid event byte bounds or tombstone");
        return contract.decode(record.topic(), utf8(record.key()), utf8(record.value()));
    }
    public static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid UTF-8"); }
    }
}
