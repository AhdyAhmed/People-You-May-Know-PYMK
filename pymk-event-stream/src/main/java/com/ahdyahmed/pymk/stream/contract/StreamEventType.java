package com.ahdyahmed.pymk.stream.contract;

/** Wire names are frozen in v1; a graph mutation is not an acceptance label. */
public enum StreamEventType {
    PROFILE_VIEW,
    SEARCH_APPEARANCE,
    INVITE_SENT,
    INVITE_ACCEPTED,
    INVITE_IGNORED,
    CONNECTION_CREATED;

    public String topic() {
        return this == CONNECTION_CREATED ? KafkaTopics.CONNECTION_EVENTS : KafkaTopics.MEMBER_EVENTS;
    }
}
