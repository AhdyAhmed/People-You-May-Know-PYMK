-- No TTL deletes: receipts must outlive every permitted source/DLT replay.
CREATE TABLE event_consumer_policy (
    consumer_name varchar(100) PRIMARY KEY,
    min_ingested_at timestamptz NOT NULL DEFAULT '1970-01-01T00:00:00Z'
);
INSERT INTO event_consumer_policy(consumer_name)
VALUES ('pymk.feature-projection.v1'), ('pymk.cache-invalidation.v1');

CREATE TABLE event_consumer_receipt (
    consumer_name varchar(100) NOT NULL REFERENCES event_consumer_policy,
    event_id uuid NOT NULL,
    content_fingerprint varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id)
);

-- Day 33's durable normalized inbox projection, not live feature counters.
-- Day 34 can consume/rebuild from this journal without rewriting training history.
CREATE TABLE event_consumer_journal (
    consumer_name varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(32) NOT NULL,
    subject_member_id bigint NOT NULL,
    target_member_id bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    aggregate_sequence bigint,
    envelope text NOT NULL,
    source_topic varchar(100) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    source_timestamp bigint NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    FOREIGN KEY (consumer_name, event_id) REFERENCES event_consumer_receipt
);
CREATE INDEX event_consumer_journal_window
    ON event_consumer_journal(consumer_name, occurred_at, subject_member_id);
