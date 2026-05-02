-- OBS-2 §M0: t_llm_trace 升级为通用 trace 实体（聚合字段）
-- See: docs/requirements/active/2026-05-02-OBS-2-trace-data-model-unification/tech-design.md §1.1
--
-- status enum (应用层校验): running | ok | error | cancelled
-- 聚合字段在 trace 结束时由 AgentLoopEngine 调 LlmTraceStore.finalizeTrace(...) 写回；
-- agent_name 与 OBS-1 既有 root_name 语义重合，本次新增是为字段自描述（M2 ETL 会同步两列）。
-- 跳号说明：observability 模块从 V37 跳到 V42 是为了避开 server 模块同期占用的 V41，
-- 两模块共享全局编号空间（参见 commit 223e5a8）。
--
-- DEFAULT 0 / 'running' 让既有 t_llm_trace 行（OBS-1 已写入的 live trace）能通过
-- NOT NULL 校验；M2 历史数据迁移会按 t_trace_span AGENT_LOOP 聚合更新真实 status。

ALTER TABLE t_llm_trace
    ADD COLUMN IF NOT EXISTS total_duration_ms BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tool_call_count   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS event_count       INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status            VARCHAR(16) NOT NULL DEFAULT 'running',
    ADD COLUMN IF NOT EXISTS error             TEXT,
    ADD COLUMN IF NOT EXISTS agent_name        VARCHAR(256);

COMMENT ON COLUMN t_llm_trace.total_duration_ms IS 'OBS-2: trace 结束时 finalize 写回 (ended_at - started_at) ms';
COMMENT ON COLUMN t_llm_trace.tool_call_count   IS 'OBS-2: 该 trace 内 t_llm_span where kind=tool 计数';
COMMENT ON COLUMN t_llm_trace.event_count       IS 'OBS-2: 该 trace 内 t_llm_span where kind=event 计数';
COMMENT ON COLUMN t_llm_trace.status            IS 'OBS-2: running | ok | error | cancelled (应用层枚举校验)';
COMMENT ON COLUMN t_llm_trace.error             IS 'OBS-2: 失败时摘要错误信息 (TEXT, 不限长)';
COMMENT ON COLUMN t_llm_trace.agent_name        IS 'OBS-2: trace 主 agent 名 (与 root_name 同语义；新加为字段自描述)';
