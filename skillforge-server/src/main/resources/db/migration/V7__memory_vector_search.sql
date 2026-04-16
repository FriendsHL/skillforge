-- pgvector extension + embedding column + HNSW index
-- pgvector is a third-party extension (https://github.com/pgvector/pgvector)
-- that must be pre-installed in the PostgreSQL cluster.
-- If unavailable, vector search is disabled and search degrades to FTS-only.
DO $$ BEGIN
  CREATE EXTENSION IF NOT EXISTS vector;
  ALTER TABLE t_memory ADD COLUMN IF NOT EXISTS embedding vector(1536);
  CREATE INDEX IF NOT EXISTS idx_memory_embedding
      ON t_memory USING hnsw (embedding vector_cosine_ops)
      WITH (m = 16, ef_construction = 64);
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'pgvector extension not available, vector search disabled (FTS-only mode): %', SQLERRM;
END $$;

-- tsvector full-text search column (GENERATED STORED, auto-updated with title/content/tags)
-- 'simple' dictionary: no stemming, works with mixed Chinese/English text
-- This does NOT require pgvector and always runs.
ALTER TABLE t_memory ADD COLUMN IF NOT EXISTS search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple',
            coalesce(title, '') || ' ' || coalesce(content, '') || ' ' || coalesce(tags, ''))
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_memory_fts
    ON t_memory USING gin(search_vector);
