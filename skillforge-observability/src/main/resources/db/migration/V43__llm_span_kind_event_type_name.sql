-- OBS-2 §M0: t_llm_span 升级为通用 span 表（kind / event_type / name 加 索引）
-- See: docs/requirements/active/2026-05-02-OBS-2-trace-data-model-unification/tech-design.md §1.2
--
-- kind enum (应用层校验): llm | tool | event
-- event_type (仅当 kind='event'): ask_user | install_confirm | compact | agent_confirm
-- name: tool name (kind='tool') / event name (kind='event') / NULL for LLM (走 model 字段)
--
-- DEFAULT 'llm' 让既有 t_llm_span 行 (source='live'/'legacy', 全部为 LLM 调用 span) 平滑
-- 落到 kind='llm'，无需 backfill。M1 后 tool span / event span 写入时显式赋 kind。
-- LLM 特有字段 (provider / model / input_blob_ref / usage_json / cache_read_tokens / ...)
-- 已为 nullable，无需改 schema。
--
-- 索引补充：原 idx_llm_span_session (session_id, started_at) 与 idx_llm_span_trace
-- (trace_id, started_at) 保留——它们服务"不分 kind 的全集 timeline"查询。新加的两个
-- (..., kind, started_at) 复合索引服务"按 kind 过滤的 timeline"（OBS-2 M3 的瀑布流
-- chips 过滤路径），覆盖范围不重叠。

ALTER TABLE t_llm_span
    ADD COLUMN IF NOT EXISTS kind       VARCHAR(16) NOT NULL DEFAULT 'llm',
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS name       VARCHAR(256);

COMMENT ON COLUMN t_llm_span.kind       IS 'OBS-2: llm | tool | event (应用层枚举校验)';
COMMENT ON COLUMN t_llm_span.event_type IS 'OBS-2: 仅 kind=event 时填; ask_user | install_confirm | compact | agent_confirm';
COMMENT ON COLUMN t_llm_span.name       IS 'OBS-2: tool name (kind=tool) / event name (kind=event) / NULL for kind=llm (走 model)';

CREATE INDEX IF NOT EXISTS idx_llm_span_session_kind
    ON t_llm_span (session_id, kind, started_at);

CREATE INDEX IF NOT EXISTS idx_llm_span_trace_kind
    ON t_llm_span (trace_id, kind, started_at);
