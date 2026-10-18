-- Day 22. Existing seeded/test vectors retain an explicit legacy identity.
ALTER TABLE member_embeddings
    ADD COLUMN embedding_version VARCHAR(100) NOT NULL DEFAULT 'placeholder-v1',
    ADD COLUMN fit_cutoff TIMESTAMPTZ;

ALTER TABLE member_embeddings ADD CONSTRAINT chk_embedding_provenance CHECK (
    length(trim(embedding_version)) > 0
    AND (fit_cutoff IS NULL OR fit_cutoff <= updated_at)
    AND (embedding_version = 'placeholder-v1' OR fit_cutoff IS NOT NULL)
);
