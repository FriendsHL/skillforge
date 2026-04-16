CREATE TABLE t_eval_scenario (
    id                    VARCHAR(36)   NOT NULL PRIMARY KEY,
    agent_id              VARCHAR(36)   NOT NULL,
    name                  VARCHAR(256)  NOT NULL,
    description           TEXT,
    category              VARCHAR(64)   DEFAULT 'session_derived',
    split                 VARCHAR(16)   DEFAULT 'held_out',
    task                  TEXT          NOT NULL,
    oracle_type           VARCHAR(32)   DEFAULT 'llm_judge',
    oracle_expected       TEXT,
    status                VARCHAR(32)   NOT NULL DEFAULT 'draft',
    source_session_id     VARCHAR(36),
    extraction_rationale  TEXT,
    created_at            TIMESTAMP,
    reviewed_at           TIMESTAMP
);

CREATE INDEX idx_eval_scenario_agent_status ON t_eval_scenario(agent_id, status);
