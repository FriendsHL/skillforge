-- OBS-4 §M1: t_llm_trace.root_trace_id SET NOT NULL —— M1 写入路径已透传 root_trace_id 后
-- 加约束，原子切换。
-- See: docs/requirements/active/2026-05-03-OBS-4-root-trace-id/tech-design.md §1.1
--
-- 为什么 V45 不直接 SET NOT NULL：
--   PgLlmTraceStore.upsertTrace 当前 INSERT SQL 不传 root_trace_id 列。如果 V45 一次
--   性 SET NOT NULL，M1 写入路径改造之前任何新 trace 都会 NOT NULL violation 失败。
--   两步部署：M0 V45 加 nullable 列让系统继续跑老路径；M1 V46（本文件）改写入路径并
--   SET NOT NULL，原子切换。
--
-- 防御性回填：极端情况下若 V45 与本 migration 之间有写入（兼容老 PgLlmTraceStore 的
-- INSERT 路径），active_root_trace_id 列写入 NULL 会导致本 migration 失败。先做一次
-- 回填（self 当 root），保证 SET NOT NULL 一定能跑通。这层回填是幂等的。
UPDATE t_llm_trace SET root_trace_id = trace_id WHERE root_trace_id IS NULL;

-- INV-1: enforcement —— 既有 root_trace_id 全部非 NULL（M0 V45 已回填，本约束是 schema 层守卫）
ALTER TABLE t_llm_trace ALTER COLUMN root_trace_id SET NOT NULL;
