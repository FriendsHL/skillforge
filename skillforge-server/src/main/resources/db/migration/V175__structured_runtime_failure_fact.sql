ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS runtime_failure_source VARCHAR(32);

ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS runtime_failure_code VARCHAR(64);

ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS runtime_retryable BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS runtime_side_effects VARCHAR(16);

-- A legacy runtime_step='retryable' does not prove that the provider emitted
-- zero SSE deltas or that the turn caused no side effects. Backfill all legacy
-- failures fail-closed and remove that unsafe retry hint.
UPDATE t_session
SET runtime_failure_source = 'unknown',
    runtime_failure_code = 'LEGACY_RUNTIME_FAILURE',
    runtime_retryable = FALSE,
    runtime_side_effects = 'possible',
    runtime_error = 'A previous runtime failure was recorded.',
    runtime_step = CASE WHEN runtime_step = 'retryable' THEN NULL ELSE runtime_step END
WHERE runtime_status = 'error'
  AND runtime_failure_source IS NULL
  AND runtime_failure_code IS NULL
  AND runtime_side_effects IS NULL;

UPDATE t_session
SET runtime_failure_source = NULL,
    runtime_failure_code = NULL,
    runtime_retryable = FALSE,
    runtime_side_effects = NULL,
    runtime_error = NULL
WHERE runtime_status IS DISTINCT FROM 'error';

ALTER TABLE t_session DROP CONSTRAINT IF EXISTS chk_session_runtime_failure_source;
ALTER TABLE t_session ADD CONSTRAINT chk_session_runtime_failure_source
    CHECK (runtime_failure_source IS NULL OR runtime_failure_source IN
           ('model_provider', 'network', 'tool', 'harness', 'user_action', 'unknown'));

ALTER TABLE t_session DROP CONSTRAINT IF EXISTS chk_session_runtime_side_effects;
ALTER TABLE t_session ADD CONSTRAINT chk_session_runtime_side_effects
    CHECK (runtime_side_effects IS NULL OR runtime_side_effects IN
           ('none', 'possible', 'observed'));

ALTER TABLE t_session DROP CONSTRAINT IF EXISTS chk_session_runtime_failure_complete;
ALTER TABLE t_session ADD CONSTRAINT chk_session_runtime_failure_complete
    CHECK (
        (runtime_status IS DISTINCT FROM 'error'
            AND runtime_failure_source IS NULL
            AND runtime_failure_code IS NULL
            AND runtime_side_effects IS NULL
            AND runtime_error IS NULL
            AND runtime_retryable = FALSE)
        OR
        (runtime_status = 'error'
            AND runtime_failure_source IS NOT NULL
            AND runtime_failure_code IS NOT NULL
            AND length(btrim(runtime_failure_code)) > 0
            AND runtime_side_effects IS NOT NULL
            AND runtime_error IS NOT NULL
            AND length(btrim(runtime_error)) > 0)
    );

ALTER TABLE t_session DROP CONSTRAINT IF EXISTS chk_session_runtime_retry_safe;
ALTER TABLE t_session ADD CONSTRAINT chk_session_runtime_retry_safe
    CHECK (runtime_retryable = FALSE OR
           (runtime_status = 'error'
               AND runtime_side_effects = 'none'
               AND runtime_failure_source IN ('model_provider', 'network', 'harness')));
