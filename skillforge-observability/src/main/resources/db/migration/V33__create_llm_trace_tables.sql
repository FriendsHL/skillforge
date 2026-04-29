-- OBS-1 §3.1/§3.2: t_llm_trace + t_llm_span 双表
-- 与现有 t_trace_span 完全独立 (旁路通道)

CREATE TABLE IF NOT EXISTS t_llm_trace (
    trace_id              VARCHAR(36)   PRIMARY KEY,
    session_id            VARCHAR(36)   NOT NULL,
    agent_id              BIGINT,
    user_id               BIGINT,
    root_name             VARCHAR(256),
    started_at            TIMESTAMPTZ   NOT NULL,
    ended_at              TIMESTAMPTZ,
    total_input_tokens    INTEGER       NOT NULL DEFAULT 0,
    total_output_tokens   INTEGER       NOT NULL DEFAULT 0,
    total_cost_usd        NUMERIC(12,6),
    source                VARCHAR(8)    NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  t_llm_trace                    IS 'OBS-1: 一行 = 一个 AGENT_LOOP root 对应的 LLM trace 维度聚合 (累加 token / cost)';
COMMENT ON COLUMN t_llm_trace.trace_id           IS '复用 TraceSpan.id (AGENT_LOOP root)';
COMMENT ON COLUMN t_llm_trace.started_at         IS '首次 insert 写入；后续 ON CONFLICT DO UPDATE 不覆盖';
COMMENT ON COLUMN t_llm_trace.ended_at           IS '每次 LLM call 完成后用 GREATEST 推进';
COMMENT ON COLUMN t_llm_trace.total_input_tokens IS 'DB 端累加 (避免 read-modify-write race)';
COMMENT ON COLUMN t_llm_trace.source             IS 'live | legacy';

CREATE INDEX IF NOT EXISTS idx_llm_trace_session
    ON t_llm_trace (session_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_trace_started
    ON t_llm_trace (started_at DESC);

CREATE TABLE IF NOT EXISTS t_llm_span (
    span_id              VARCHAR(36)   PRIMARY KEY,
    trace_id             VARCHAR(36)   NOT NULL,
    parent_span_id       VARCHAR(36),
    session_id           VARCHAR(36)   NOT NULL,
    agent_id             BIGINT,
    provider             VARCHAR(32),
    model                VARCHAR(128),
    iteration_index      INTEGER       NOT NULL DEFAULT 0,
    stream               BOOLEAN       NOT NULL DEFAULT TRUE,
    input_summary        TEXT,
    output_summary       TEXT,
    input_blob_ref       TEXT,
    output_blob_ref      TEXT,
    raw_sse_blob_ref     TEXT,
    blob_status          VARCHAR(16),
    input_tokens         INTEGER       NOT NULL DEFAULT 0,
    output_tokens        INTEGER       NOT NULL DEFAULT 0,
    cache_read_tokens    INTEGER,
    usage_json           JSONB,
    cost_usd             NUMERIC(12,6),
    latency_ms           BIGINT        NOT NULL DEFAULT 0,
    started_at           TIMESTAMPTZ   NOT NULL,
    ended_at             TIMESTAMPTZ,
    finish_reason        VARCHAR(32),
    request_id           VARCHAR(128),
    reasoning_content    TEXT,
    error                TEXT,
    error_type           VARCHAR(64),
    tool_use_id          VARCHAR(64),
    attributes_json      JSONB,
    source               VARCHAR(8)    NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uniq_llm_span_trace_span UNIQUE (trace_id, span_id)
);

COMMENT ON TABLE  t_llm_span                IS 'OBS-1: 一次 LLM 调用 (live 或 legacy ETL) 的 span';
COMMENT ON COLUMN t_llm_span.provider       IS 'live: LlmProvider.getName(); legacy: ETL resolveProviderFromModelId; 单一来源 ProviderName.CANONICAL';
COMMENT ON COLUMN t_llm_span.blob_status    IS 'ok | legacy | write_failed | truncated (UI 据此区分)';
COMMENT ON COLUMN t_llm_span.input_summary  IS '<=32KB; 超过截断 + 末尾标记';
COMMENT ON COLUMN t_llm_span.output_summary IS '<=32KB; 超过截断 + 末尾标记';
COMMENT ON COLUMN t_llm_span.input_blob_ref IS 'live 写盘成功填; legacy/write_failed 时 NULL';
COMMENT ON COLUMN t_llm_span.attributes_json IS 'free-form: compact_call/sse_truncated/blob_truncated 等';

CREATE INDEX IF NOT EXISTS idx_llm_span_trace
    ON t_llm_span (trace_id, started_at);
CREATE INDEX IF NOT EXISTS idx_llm_span_session
    ON t_llm_span (session_id, started_at);
CREATE INDEX IF NOT EXISTS idx_llm_span_started
    ON t_llm_span (started_at DESC);
