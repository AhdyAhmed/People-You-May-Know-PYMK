package com.ahdyahmed.pymk.stream.contract;

import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

/** Explicit development topology; no auto-configuration or connection on class loading. */
public final class KafkaTopics {
    public static final String MEMBER_EVENTS = "pymk.member-events.v1";
    public static final String CONNECTION_EVENTS = "pymk.connection-events.v1";
    public static final String FEATURE_PROJECTION_GROUP = "pymk.feature-projection.v1";
    public static final String CACHE_INVALIDATION_GROUP = "pymk.cache-invalidation.v1";
    public static final int DEVELOPMENT_PARTITIONS = 3;
    public static final long RETENTION_MS = Duration.ofDays(7).toMillis();

    private KafkaTopics() { }

    public static List<NewTopic> developmentTopics() {
        return List.of(developmentTopic(MEMBER_EVENTS), developmentTopic(CONNECTION_EVENTS));
    }

    private static NewTopic developmentTopic(String name) {
        return TopicBuilder.name(name).partitions(DEVELOPMENT_PARTITIONS).replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(RETENTION_MS))
                .config(TopicConfig.RETENTION_BYTES_CONFIG, "-1")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "3600000")
                .config(TopicConfig.MESSAGE_TIMESTAMP_TYPE_CONFIG, "LogAppendTime")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .build();
    }
}
