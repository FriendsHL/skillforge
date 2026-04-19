CREATE TABLE t_skill_ab_run (
    id             VARCHAR(36)    NOT NULL PRIMARY KEY,
    parent_skill_id BIGINT        NOT NULL,
    candidate_skill_id BIGINT     NOT NULL,
    agent_id       VARCHAR(36)    NOT NULL,
    baseline_eval_run_id VARCHAR(36),
    status         VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    baseline_pass_rate DOUBLE PRECISION,
    candidate_pass_rate DOUBLE PRECISION,
    delta_pass_rate DOUBLE PRECISION,
    promoted       BOOLEAN        NOT NULL DEFAULT FALSE,
    skip_reason    VARCHAR(256),
    failure_reason TEXT,
    ab_scenario_results_json TEXT,
    triggered_by_user_id BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ
);
CREATE INDEX idx_skill_ab_run_candidate ON t_skill_ab_run (candidate_skill_id);
