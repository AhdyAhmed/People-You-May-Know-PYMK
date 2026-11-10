-- PYMK control-plane telemetry, independent of the Airflow metadata schema.
ALTER TABLE pymk_workflow_work
    ADD COLUMN workflow varchar(40),
    ADD COLUMN interval_end timestamptz,
    ADD COLUMN started_at timestamptz,
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN last_duration_seconds double precision,
    ADD COLUMN failure_count integer NOT NULL DEFAULT 0;
