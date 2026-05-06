ALTER TABLE t_eval_task_item
    ADD COLUMN IF NOT EXISTS quality_score NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS efficiency_score NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS latency_score NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS cost_score NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS cost_usd NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS score_formula_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS score_breakdown_json TEXT;

COMMENT ON COLUMN t_eval_task_item.quality_score IS
    'EVAL-V2 M4: answer quality score (0-100), currently derived from judge outcomeScore.';
COMMENT ON COLUMN t_eval_task_item.efficiency_score IS
    'EVAL-V2 M4: execution efficiency score (0-100), derived from loop/tool usage.';
COMMENT ON COLUMN t_eval_task_item.latency_score IS
    'EVAL-V2 M4: latency dimension score (0-100), normalized against performance_threshold_ms.';
COMMENT ON COLUMN t_eval_task_item.cost_score IS
    'EVAL-V2 M4: cost dimension score (0-100), normalized against global cost threshold.';
COMMENT ON COLUMN t_eval_task_item.cost_usd IS
    'EVAL-V2 M4: observed total USD cost aggregated from the scenario root trace tree.';
COMMENT ON COLUMN t_eval_task_item.score_formula_version IS
    'EVAL-V2 M4: versioned aggregate scoring formula identifier.';
COMMENT ON COLUMN t_eval_task_item.score_breakdown_json IS
    'EVAL-V2 M4: JSON payload storing formula weights, raw observations, dimension scores, and applied caps.';
