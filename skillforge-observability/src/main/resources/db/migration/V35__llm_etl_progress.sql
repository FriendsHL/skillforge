-- OBS-1 §3.4 (R2-W5): always-create progress table for the Java-mode legacy ETL.
-- mode=java only; mode=off / mode=flyway 不消费这张表。
CREATE TABLE IF NOT EXISTS t_llm_etl_progress (
    batch_lo     BIGINT      NOT NULL,
    batch_hi     BIGINT      NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       VARCHAR(8)  NOT NULL,
    PRIMARY KEY (batch_lo, batch_hi)
);

COMMENT ON TABLE t_llm_etl_progress IS 'OBS-1: Java-mode ETL 断点续跑用 (mode=java 时被 LegacyLlmCallEtlService 消费)';
