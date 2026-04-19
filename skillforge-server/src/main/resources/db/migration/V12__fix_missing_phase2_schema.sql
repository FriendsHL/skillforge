-- V12: Add missing partial unique index on t_prompt_version (not included in V4).
-- Without this, concurrent promotion requests can create two 'active' rows for the same agent.

-- Step 1: Deduplicate any existing double-active rows before creating the unique index.
-- No-op if no duplicates exist (safe to run in any environment).
DO $$
BEGIN
    UPDATE t_prompt_version pv
    SET status = 'deprecated',
        deprecated_at = NOW()
    WHERE status = 'active'
      AND id <> (
          SELECT id
          FROM t_prompt_version
          WHERE agent_id = pv.agent_id
            AND status = 'active'
          ORDER BY promoted_at DESC NULLS LAST, id DESC
          LIMIT 1
      );
END $$;

-- Step 2: Enforce at most one active prompt version per agent.
-- The index turns any concurrent-promotion race into a unique-constraint violation
-- caught at DB level (DataIntegrityViolationException → 409 in PromptPromotionService).
CREATE UNIQUE INDEX IF NOT EXISTS uq_pv_agent_active
    ON t_prompt_version (agent_id)
    WHERE status = 'active';
