CREATE TABLE t_skill_evolution_run (
    id                  VARCHAR(36)   NOT NULL PRIMARY KEY,
    skill_id            BIGINT        NOT NULL,
    forked_skill_id     BIGINT,
    ab_run_id           VARCHAR(36),
    agent_id            VARCHAR(36),
    status              VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    success_rate_before DOUBLE PRECISION,
    usage_count_before  BIGINT,
    improved_skill_md   TEXT,
    evolution_reasoning TEXT,
    failure_reason      TEXT,
    triggered_by_user_id BIGINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ
);
CREATE INDEX idx_skill_evolution_run_skill ON t_skill_evolution_run (skill_id);
