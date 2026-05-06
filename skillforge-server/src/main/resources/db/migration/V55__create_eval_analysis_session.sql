CREATE TABLE IF NOT EXISTS t_eval_analysis_session (
    id            BIGSERIAL PRIMARY KEY,
    session_id    VARCHAR(36) NOT NULL,
    task_id       VARCHAR(36) NULL,
    task_item_id  BIGINT NULL,
    scenario_id   VARCHAR(64) NULL,
    analysis_type VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_eval_analysis_session_session
        FOREIGN KEY (session_id) REFERENCES t_session(id),
    CONSTRAINT fk_eval_analysis_session_task
        FOREIGN KEY (task_id) REFERENCES t_eval_task(id) ON DELETE SET NULL,
    CONSTRAINT fk_eval_analysis_session_item
        FOREIGN KEY (task_item_id) REFERENCES t_eval_task_item(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_analysis_task ON t_eval_analysis_session(task_id);
CREATE INDEX IF NOT EXISTS idx_eval_analysis_item ON t_eval_analysis_session(task_item_id);
CREATE INDEX IF NOT EXISTS idx_eval_analysis_scenario ON t_eval_analysis_session(scenario_id);
