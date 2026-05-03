-- OBS-4 §M0: t_session 加 active_root_trace_id —— 当前 user message 处理期间的 active root trace
-- See: docs/requirements/active/2026-05-03-OBS-4-root-trace-id/tech-design.md §1.2
--
-- 语义：
--   • 每次收到新 user message → 由 ChatService 清空（边界重置）
--   • 主 agent 第一个 trace 创建时 → 由 ChatService 回填为 trace_id（自己当 root）
--   • 主 agent 后续 trace 创建时 → 读这一字段继承同一 root（收 child 结果做汇总等场景）
--   • spawn child session 时 → 复制父 session 的当前值给 child（child 内部 trace 继承同一 root）
--
-- 默认 NULL：老 session 不参与 root_trace_id 链路，新 trace 走完整继承机制。
ALTER TABLE t_session ADD COLUMN IF NOT EXISTS active_root_trace_id VARCHAR(36);

COMMENT ON COLUMN t_session.active_root_trace_id IS 'OBS-4: 当前 user message 处理期间的 active root trace id (跨 agent / 跨 session trace 串联根)。每个 user message 起点重置，主 agent 首个 trace 回填为自身 trace_id，spawn child 时复制给 child 让 child 内部 trace 继承同一 root。NULL = 老 session 或当前无 active 处理流程。';
