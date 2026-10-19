-- Legacy tables remain writable for the Day 17 workflow. Release rows are staged
-- independently; serving views switch together through one singleton pointer.
CREATE TABLE pymk_source_revision (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0
);
INSERT INTO pymk_source_revision(singleton) VALUES (true);
CREATE FUNCTION pymk_bump_source_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE pymk_source_revision SET revision = revision + 1;
    RETURN NULL;
END $$;
CREATE TRIGGER members_source_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON members
FOR EACH STATEMENT EXECUTE FUNCTION pymk_bump_source_revision();
CREATE TRIGGER connections_source_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON connections
FOR EACH STATEMENT EXECUTE FUNCTION pymk_bump_source_revision();
CREATE TRIGGER events_source_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON member_events
FOR EACH STATEMENT EXECUTE FUNCTION pymk_bump_source_revision();
CREATE TABLE pymk_embedding_transforms (
    embedding_version varchar(100) PRIMARY KEY,
    transform_sha256 varchar(64) NOT NULL,
    fit_cutoff timestamptz NOT NULL,
    artifact bytea NOT NULL
);
CREATE TABLE pymk_serving_releases (
    release_id varchar(100) PRIMARY KEY,
    manifest_sha256 varchar(64) NOT NULL,
    manifest jsonb NOT NULL,
    embedding_version varchar(100) NOT NULL REFERENCES pymk_embedding_transforms,
    feature_as_of timestamptz NOT NULL,
    source_fingerprint text NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('STAGING', 'STAGED', 'READY')),
    last_member_id bigint NOT NULL DEFAULT 0,
    vectors_loaded boolean NOT NULL DEFAULT false,
    model_version varchar(128),
    model_bytes bytea,
    model_metadata bytea,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (state <> 'READY' OR (model_version IS NOT NULL AND model_bytes IS NOT NULL AND model_metadata IS NOT NULL))
);
CREATE TABLE pymk_release_embeddings (
    release_id varchar(100) NOT NULL REFERENCES pymk_serving_releases,
    member_id bigint NOT NULL REFERENCES members,
    embedding vector(128) NOT NULL,
    updated_at timestamptz NOT NULL,
    embedding_version varchar(100) NOT NULL,
    fit_cutoff timestamptz NOT NULL,
    PRIMARY KEY (release_id, member_id),
    CHECK (fit_cutoff <= updated_at)
);
CREATE INDEX idx_release_embedding_ann ON pymk_release_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE TABLE pymk_release_features (
    release_id varchar(100) NOT NULL REFERENCES pymk_serving_releases,
    member_id bigint NOT NULL REFERENCES members,
    candidate_id bigint NOT NULL REFERENCES members,
    mutual_connection_count integer NOT NULL CHECK (mutual_connection_count >= 0),
    same_company boolean NOT NULL,
    same_school boolean NOT NULL,
    same_geo boolean NOT NULL,
    embedding_cosine_sim double precision NOT NULL CHECK (embedding_cosine_sim BETWEEN -1 AND 1),
    profile_view_count_last_30d integer NOT NULL CHECK (profile_view_count_last_30d >= 0),
    feature_as_of timestamptz NOT NULL,
    computed_at timestamptz NOT NULL,
    PRIMARY KEY (release_id, member_id, candidate_id),
    CHECK (member_id <> candidate_id AND feature_as_of <= computed_at)
);
CREATE TABLE pymk_active_release (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    release_id varchar(100) REFERENCES pymk_serving_releases,
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0)
);
INSERT INTO pymk_active_release(singleton) VALUES (true);
CREATE TABLE pymk_legacy_release_model (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    model_version varchar(128) NOT NULL,
    model_bytes bytea NOT NULL,
    model_metadata bytea NOT NULL
);
CREATE TABLE pymk_release_exports (
    csv_sha256 varchar(64) PRIMARY KEY,
    release_id varchar(100) NOT NULL REFERENCES pymk_serving_releases,
    label_window_end timestamptz NOT NULL,
    exported_at timestamptz NOT NULL DEFAULT now()
);

CREATE VIEW pymk_serving_embeddings AS
SELECT e.* FROM member_embeddings e
WHERE (SELECT release_id FROM pymk_active_release) IS NULL
UNION ALL
SELECT e.member_id, e.embedding, e.updated_at, e.embedding_version, e.fit_cutoff
FROM pymk_release_embeddings e JOIN pymk_active_release a ON e.release_id = a.release_id;

CREATE VIEW pymk_serving_features AS
SELECT f.* FROM pymk_features f
WHERE (SELECT release_id FROM pymk_active_release) IS NULL
UNION ALL
SELECT f.member_id, f.candidate_id, f.mutual_connection_count,
       f.same_company, f.same_school, f.same_geo, f.embedding_cosine_sim,
       f.profile_view_count_last_30d, f.feature_as_of, f.computed_at
FROM pymk_release_features f JOIN pymk_active_release a ON f.release_id = a.release_id;
