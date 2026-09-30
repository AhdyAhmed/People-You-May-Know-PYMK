-- V2: pgvector + member_embeddings (Day 5, PYMK_ROADMAP.md Week 1).
--
-- CREATE EXTENSION runs here, not only in infra/init-sql/01-enable-pgvector.sql:
-- that init script only fires for containers started via docker-entrypoint-initdb.d
-- (i.e. docker-compose's fresh volume). Testcontainers-based tests, and any
-- Postgres someone points this app at directly, never run it - so the
-- migration has to be self-sufficient regardless of how Postgres was started.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE member_embeddings (
    member_id   BIGINT PRIMARY KEY REFERENCES members (id),
    embedding   vector(128) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW over ivfflat: ivfflat's clusters are trained from whatever rows exist
-- at CREATE INDEX time, so building it before the table is loaded gives a
-- low-quality index (a classic pgvector footgun). HNSW builds incrementally
-- and handles inserts/updates from EmbeddingRefreshJob (Day 23) correctly
-- without a rebuild step. Cosine ops to match embeddingCosineSim
-- (pymk_features, Day 15).
CREATE INDEX idx_member_embeddings_ann
    ON member_embeddings USING hnsw (embedding vector_cosine_ops);
