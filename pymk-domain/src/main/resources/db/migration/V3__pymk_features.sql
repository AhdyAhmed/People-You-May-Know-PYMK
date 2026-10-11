-- V3: offline pair feature store (Day 15, PYMK_ROADMAP.md Week 3).
--
-- The schema migration lives in pymk-domain so every runnable application
-- applies the complete shared database schema. Pair direction is intentional:
-- member -> candidate signals such as profile views are not necessarily equal
-- to candidate -> member signals.
CREATE TABLE pymk_features (
    member_id                    BIGINT NOT NULL REFERENCES members (id),
    candidate_id                 BIGINT NOT NULL REFERENCES members (id),
    mutual_connection_count      INTEGER NOT NULL,
    same_company                 BOOLEAN NOT NULL,
    same_school                  BOOLEAN NOT NULL,
    same_geo                     BOOLEAN NOT NULL,
    embedding_cosine_sim         DOUBLE PRECISION NOT NULL,
    profile_view_count_last_30d  INTEGER NOT NULL,
    feature_as_of                TIMESTAMPTZ NOT NULL,
    computed_at                  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (member_id, candidate_id),
    CONSTRAINT chk_pymk_features_not_self
        CHECK (member_id <> candidate_id),
    CONSTRAINT chk_pymk_features_mutual_count
        CHECK (mutual_connection_count >= 0),
    CONSTRAINT chk_pymk_features_cosine
        CHECK (embedding_cosine_sim BETWEEN -1.0 AND 1.0),
    CONSTRAINT chk_pymk_features_profile_views
        CHECK (profile_view_count_last_30d >= 0),
    CONSTRAINT chk_pymk_features_snapshot_time
        CHECK (feature_as_of <= computed_at)
);

-- The primary key serves member-side online reads. These indexes support
-- candidate-side analysis and finding stale snapshots for batch refreshes.
CREATE INDEX idx_pymk_features_candidate ON pymk_features (candidate_id);
CREATE INDEX idx_pymk_features_as_of ON pymk_features (feature_as_of);
