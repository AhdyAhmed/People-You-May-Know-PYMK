-- Dataset IDs identify immutable, release-bound v2 CSV/provenance publications.
-- Kept separate from v1 exports so v2 files cannot satisfy L1 promotion gates.
CREATE TABLE pymk_training_datasets_v2 (
    dataset_id varchar(100) PRIMARY KEY,
    release_id varchar(100) NOT NULL REFERENCES pymk_serving_releases,
    csv_sha256 varchar(64) NOT NULL,
    provenance jsonb NOT NULL,
    label_window_end timestamptz NOT NULL,
    exported_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_invite_sent_labels ON member_events(actor_member_id, target_member_id, occurred_at)
WHERE type = 'INVITE_SENT';
