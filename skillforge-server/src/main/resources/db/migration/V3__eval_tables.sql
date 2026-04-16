-- EvalRun table
CREATE TABLE t_eval_run (
    id VARCHAR(36) PRIMARY KEY,
    agent_definition_id VARCHAR(36) NOT NULL,
    scenario_set_version VARCHAR(64),
    status VARCHAR(32),
    error_message TEXT,
    scenario_results_json TEXT,
    improvement_suggestions_json TEXT,
    overall_pass_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_oracle_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_scenarios INTEGER NOT NULL DEFAULT 0,
    passed_scenarios INTEGER NOT NULL DEFAULT 0,
    failed_scenarios INTEGER NOT NULL DEFAULT 0,
    timeout_scenarios INTEGER NOT NULL DEFAULT 0,
    veto_scenarios INTEGER NOT NULL DEFAULT 0,
    attr_skill_missing INTEGER NOT NULL DEFAULT 0,
    attr_skill_exec_failure INTEGER NOT NULL DEFAULT 0,
    attr_prompt_quality INTEGER NOT NULL DEFAULT 0,
    attr_context_overflow INTEGER NOT NULL DEFAULT 0,
    attr_performance INTEGER NOT NULL DEFAULT 0,
    primary_attribution VARCHAR(32),
    consecutive_decline_count INTEGER NOT NULL DEFAULT 0,
    triggered_by_user_id BIGINT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    collab_run_id VARCHAR(36)
);

-- EvalSession table (isolated from t_session)
CREATE TABLE t_eval_session (
    session_id VARCHAR(36) PRIMARY KEY,
    eval_run_id VARCHAR(36) NOT NULL,
    scenario_id VARCHAR(36) NOT NULL,
    status VARCHAR(16),
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);

-- Add runType to t_collab_run
ALTER TABLE t_collab_run ADD COLUMN run_type VARCHAR(16) DEFAULT 'user';
