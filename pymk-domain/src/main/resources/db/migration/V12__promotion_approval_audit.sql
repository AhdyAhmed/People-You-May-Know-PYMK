-- Approval evidence is committed atomically with the expected-epoch release switch.
CREATE TABLE pymk_promotion_audit (
    activated_epoch bigint PRIMARY KEY CHECK (activated_epoch > 0),
    release_id varchar(100) NOT NULL REFERENCES pymk_serving_releases(release_id),
    approval_sha256 varchar(64) NOT NULL CHECK (approval_sha256 ~ '^[a-f0-9]{64}$'),
    approval jsonb NOT NULL,
    activated_at timestamptz NOT NULL DEFAULT now()
);
