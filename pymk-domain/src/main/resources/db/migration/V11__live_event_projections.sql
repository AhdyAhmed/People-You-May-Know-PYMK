-- Versioned live state is deliberately independent of historical pymk_features.
CREATE TABLE live_projection_generation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    knowledge_cutoff timestamptz,
    window_start timestamptz,
    window_end timestamptz
);
INSERT INTO live_projection_generation DEFAULT VALUES;
CREATE TABLE live_projection_active (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    generation bigint NOT NULL REFERENCES live_projection_generation
);
INSERT INTO live_projection_active VALUES (true, 1);
CREATE TABLE live_member_event (
    generation bigint NOT NULL REFERENCES live_projection_generation,
    event_id uuid NOT NULL,
    event_type varchar(32) NOT NULL CHECK (event_type IN ('PROFILE_VIEW', 'INVITE_SENT', 'INVITE_ACCEPTED')),
    subject_member_id bigint NOT NULL,
    target_member_id bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    PRIMARY KEY (generation, event_id)
);
CREATE INDEX live_member_event_window ON live_member_event
    (generation, subject_member_id, target_member_id, occurred_at);
CREATE TABLE live_connection_state (
    first_member_id bigint NOT NULL,
    second_member_id bigint NOT NULL,
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 0),
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    PRIMARY KEY (first_member_id, second_member_id)
);
CREATE TABLE member_cache_epoch (
    member_id bigint PRIMARY KEY,
    epoch bigint NOT NULL CHECK (epoch > 0)
);

-- Same transaction as the consumer receipt. No Redis availability dependency.
CREATE FUNCTION apply_live_connection(first_id bigint, second_id bigint, sequence_value bigint,
    event_time timestamptz, ingestion_time timestamptz) RETURNS void LANGUAGE plpgsql AS $$
DECLARE changed integer;
BEGIN
        INSERT INTO live_connection_state VALUES (first_id, second_id,
            sequence_value, event_time, ingestion_time)
        ON CONFLICT (first_member_id, second_member_id) DO UPDATE SET
            aggregate_sequence = EXCLUDED.aggregate_sequence, occurred_at = EXCLUDED.occurred_at,
            ingested_at = EXCLUDED.ingested_at
        WHERE live_connection_state.aggregate_sequence < EXCLUDED.aggregate_sequence;
        GET DIAGNOSTICS changed = ROW_COUNT;
        IF changed > 0 THEN
            INSERT INTO member_cache_epoch(member_id, epoch)
            SELECT member_id, 1 FROM (
                SELECT first_id AS member_id UNION SELECT second_id
                UNION SELECT connected_member_id FROM connections
                    WHERE member_id IN (first_id, second_id)
                UNION SELECT c2.connected_member_id FROM connections c1
                    JOIN connections c2 ON c2.member_id = c1.connected_member_id
                    WHERE c1.member_id IN (first_id, second_id)
            ) affected ORDER BY member_id
            ON CONFLICT (member_id) DO UPDATE SET epoch = member_cache_epoch.epoch + 1;
        END IF;
END $$;

CREATE FUNCTION project_live_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active_generation bigint;
BEGIN
    IF NEW.consumer_name = 'pymk.feature-projection.v1'
       AND NEW.event_type IN ('PROFILE_VIEW', 'INVITE_SENT', 'INVITE_ACCEPTED') THEN
        SELECT generation INTO active_generation FROM live_projection_active WHERE singleton FOR SHARE;
        INSERT INTO live_member_event VALUES (active_generation, NEW.event_id, NEW.event_type,
            NEW.subject_member_id, NEW.target_member_id, NEW.occurred_at, NEW.ingested_at)
        ON CONFLICT DO NOTHING;
    ELSIF NEW.consumer_name = 'pymk.cache-invalidation.v1' AND NEW.event_type = 'CONNECTION_CREATED' THEN
        PERFORM apply_live_connection(NEW.subject_member_id, NEW.target_member_id,
            NEW.aggregate_sequence, NEW.occurred_at, NEW.ingested_at);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER live_journal_projection AFTER INSERT ON event_consumer_journal
    FOR EACH ROW EXECUTE FUNCTION project_live_journal();

-- Bootstrap before activation without deleting receipts or replaying side effects.
INSERT INTO live_member_event
SELECT 1, event_id, event_type, subject_member_id, target_member_id, occurred_at, ingested_at
FROM event_consumer_journal WHERE consumer_name = 'pymk.feature-projection.v1'
    AND event_type IN ('PROFILE_VIEW', 'INVITE_SENT', 'INVITE_ACCEPTED');
INSERT INTO live_connection_state
SELECT DISTINCT ON (subject_member_id, target_member_id)
    subject_member_id, target_member_id, aggregate_sequence, occurred_at, ingested_at
FROM event_consumer_journal WHERE consumer_name = 'pymk.cache-invalidation.v1'
    AND event_type = 'CONNECTION_CREATED'
ORDER BY subject_member_id, target_member_id, aggregate_sequence DESC;
INSERT INTO member_cache_epoch
SELECT member_id, 1 FROM (
    SELECT first_member_id AS member_id FROM live_connection_state
    UNION SELECT second_member_id FROM live_connection_state
    UNION SELECT c.connected_member_id FROM connections c JOIN live_connection_state s
        ON c.member_id IN (s.first_member_id, s.second_member_id)
    UNION SELECT c2.connected_member_id FROM connections c1
        JOIN connections c2 ON c2.member_id = c1.connected_member_id
        JOIN live_connection_state s ON c1.member_id IN (s.first_member_id, s.second_member_id)
) affected;
