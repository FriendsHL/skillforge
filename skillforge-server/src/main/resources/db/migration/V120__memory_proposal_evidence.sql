ALTER TABLE t_memory_proposal
    ADD COLUMN IF NOT EXISTS evidence_json jsonb;

COMMENT ON COLUMN t_memory_proposal.evidence_json IS
    'Optional evidence objects cited by the LLM, e.g. sessionId/seqNo/quote for transcript-backed reflections.';
