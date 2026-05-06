ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS parent_scenario_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_eval_scenario_parent
    ON t_eval_scenario(parent_scenario_id);

CREATE INDEX IF NOT EXISTS idx_eval_scenario_agent_version
    ON t_eval_scenario(agent_id, version DESC, created_at DESC);
