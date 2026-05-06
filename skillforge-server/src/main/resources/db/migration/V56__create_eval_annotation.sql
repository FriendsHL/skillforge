CREATE TABLE IF NOT EXISTS t_eval_annotation (
    id BIGSERIAL PRIMARY KEY,
    task_item_id BIGINT NOT NULL REFERENCES t_eval_task_item(id) ON DELETE CASCADE,
    annotator_id BIGINT NOT NULL,
    original_score NUMERIC(5,2),
    corrected_score NUMERIC(5,2),
    corrected_expected TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_eval_annotation_status_created
    ON t_eval_annotation(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_eval_annotation_task_item
    ON t_eval_annotation(task_item_id);
