-- OBS-2 §M0: t_session_message 加 trace_id 列 + (session_id, trace_id) 索引
-- See: docs/requirements/active/2026-05-02-OBS-2-trace-data-model-unification/tech-design.md §1.3
--
-- 历史行 trace_id = NULL（Q6 决策：不 backfill）；M1 后由 ChatService / AgentLoopEngine
-- 透传 traceId 到 ChatEventBroadcaster.messageAppended(sessionId, traceId, message)。
-- 索引复用既有 (session_id, ...) 前缀模式，支持按 trace 拉 messages timeline / replay。

ALTER TABLE t_session_message
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_session_message_trace
    ON t_session_message (session_id, trace_id);
