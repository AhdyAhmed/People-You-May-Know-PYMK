-- PYMK-owned control-plane evidence. Airflow continues to own its separate metadata DB.
CREATE TABLE pymk_workflow_work (
    output_version varchar(80) NOT NULL,
    step varchar(40) NOT NULL,
    logical_key varchar(64) NOT NULL UNIQUE,
    artifact_root text NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED', 'BLOCKED')),
    attempts integer NOT NULL DEFAULT 1,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (output_version, step)
);
