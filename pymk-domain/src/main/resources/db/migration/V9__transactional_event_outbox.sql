-- Receipts survive publication: event identity cannot be reused after delivery.
-- Deliberately no member FK: historical receipts survive member deletion.
CREATE TABLE event_outbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    content_fingerprint varchar(64) NOT NULL,
    topic varchar(100) NOT NULL,
    aggregate_key varchar(50) NOT NULL,
    envelope text NOT NULL,
    ingested_at timestamptz NOT NULL,
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner uuid,
    lease_until timestamptz,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);
CREATE INDEX event_outbox_pending ON event_outbox(id) WHERE published_at IS NULL;
CREATE INDEX event_outbox_aggregate_pending ON event_outbox(topic, aggregate_key, id)
    WHERE published_at IS NULL;
CREATE TABLE event_aggregate_sequence (
    aggregate_key varchar(50) PRIMARY KEY,
    sequence bigint NOT NULL CHECK (sequence > 0)
);
