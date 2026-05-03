-- OBS-4 §M0: t_llm_trace 加 root_trace_id —— 跨 agent / 跨 session trace 串联的一等公民字段
-- See: docs/requirements/active/2026-05-03-OBS-4-root-trace-id/tech-design.md §1.1
--
-- 语义：
--   • 主 agent 第一个 trace（user message 起点）：root_trace_id = self.trace_id（自己当 root）
--   • 主 agent 后续 trace（同 user message 内）：继承 session.active_root_trace_id
--   • subagent 任意 trace（含递归 child of child）：继承父 session 在 spawn 时的 active_root_trace_id
--   • 一个 root_trace_id 在数据库 SQL 一次查询就能拿到一次完整调研的执行链
--
-- 历史数据回填策略（决策 Q2）：直接 root_trace_id = trace_id —— 老 trace 自己当 root，
-- unified view 退化为单 trace 视图（跟 OBS-2 当前体验等同）。
-- 不做时间窗启发式 backfill（启发式风险高，收益小）。
--
-- 跳号说明：observability V44 被 server 模块占用（V44__add_active_root_trace_id_to_session.sql），
-- 本 migration 用 V45。两模块共享全局编号空间（参见 commit 223e5a8）。

-- Step 1: 加列 nullable（M1 写入路径改完后由 V46 SET NOT NULL）
--
-- 为什么不在本 migration 直接 SET NOT NULL：
--   PgLlmTraceStore.upsertTrace 当前 INSERT SQL 不传 root_trace_id 列。如果 V45 一次
--   性 SET NOT NULL，M1 写入路径改造之前任何新 trace 都会 NOT NULL violation 失败。
--   两步部署：M0 加 nullable 列让系统继续跑老路径；M1 同 commit 加 V46 改写入路径并
--   SET NOT NULL，原子切换。
ALTER TABLE t_llm_trace
    ADD COLUMN IF NOT EXISTS root_trace_id VARCHAR(36);

-- Step 2: 历史数据回填 —— 老 trace 自己当 root（决策 Q2）
UPDATE t_llm_trace SET root_trace_id = trace_id WHERE root_trace_id IS NULL;

-- Step 3: 查询索引 —— 按 root_trace_id 拿整树（OBS-4 M2 read API 主路径）
CREATE INDEX IF NOT EXISTS idx_llm_trace_root ON t_llm_trace (root_trace_id, started_at);

COMMENT ON COLUMN t_llm_trace.root_trace_id IS 'OBS-4: 跨 agent / 跨 session trace 串联根 ID。同一 user message 内主 agent 所有 trace + 派出的所有 subagent (含递归 child of child) 全部共享同一 root_trace_id。一次 SQL 按它查能拿一次完整调研的执行链。Immutable: 一旦写入不再改。';
