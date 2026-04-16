-- P2-1: Prompt versioning and A/B testing tables for Self-Improve Pipeline

CREATE TABLE t_prompt_version (
    id                    VARCHAR(36)  PRIMARY KEY,
    agent_id              VARCHAR(36)  NOT NULL,
    content               TEXT         NOT NULL,
    version_number        INTEGER      NOT NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'candidate',
    source                VARCHAR(32)  NOT NULL DEFAULT 'auto_improve',
    source_eval_run_id    VARCHAR(36),
    ab_run_id             VARCHAR(36),
    delta_pass_rate       DOUBLE PRECISION,
    baseline_pass_rate    DOUBLE PRECISION,
    improvement_rationale TEXT,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    promoted_at           TIMESTAMP,
    deprecated_at         TIMESTAMP,
    CONSTRAINT uq_agent_version UNIQUE (agent_id, version_number)
);
CREATE INDEX idx_pv_agent_id     ON t_prompt_version (agent_id);
CREATE INDEX idx_pv_agent_status ON t_prompt_version (agent_id, status);

CREATE TABLE t_prompt_ab_run (
    id                       VARCHAR(36)  PRIMARY KEY,
    agent_id                 VARCHAR(36)  NOT NULL,
    prompt_version_id        VARCHAR(36)  NOT NULL,
    baseline_eval_run_id     VARCHAR(36)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    baseline_pass_rate       DOUBLE PRECISION,
    candidate_pass_rate      DOUBLE PRECISION,
    delta_pass_rate          DOUBLE PRECISION,
    promoted                 BOOLEAN      NOT NULL DEFAULT FALSE,
    skip_reason              VARCHAR(128),
    failure_reason           TEXT,
    ab_scenario_results_json TEXT,
    triggered_by_user_id     BIGINT,
    started_at               TIMESTAMP,
    completed_at             TIMESTAMP
);
CREATE INDEX idx_par_agent_id          ON t_prompt_ab_run (agent_id);
CREATE INDEX idx_par_prompt_version_id ON t_prompt_ab_run (prompt_version_id);
CREATE UNIQUE INDEX uq_ab_run_agent_active ON t_prompt_ab_run (agent_id)
    WHERE status IN ('PENDING', 'RUNNING');

ALTER TABLE t_agent
    ADD COLUMN IF NOT EXISTS active_prompt_version_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS auto_improve_paused      BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ab_decline_count         INTEGER   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_promoted_at         TIMESTAMP;
