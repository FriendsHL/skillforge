-- EVAL-V2 Q1: t_session 加 source_scenario_id —— 关联到产生此会话的 eval scenario
--
-- 语义：
--   • 由 AnalyzeCaseModal 创建的"分析会话"在 createSession 时填入对应 EvalScenarioEntity.id。
--   • 用于 ScenarioDetailDrawer 反向查找"已有哪些分析会话讨论过这个 case"，避免重复发起。
--   • 默认 NULL：所有非分析路径创建的会话不参与此关联。
--
-- 索引说明：scenario 详情面板按 source_scenario_id 拉历史 sessions 是高频读，
-- 但分布严重倾斜（绝大多数 NULL），所以加 partial index（PostgreSQL 支持）。
-- Length 64 (not 36): UUIDs are 36 chars, but BaseScenarioService.SAFE_ID
-- permits up to 64-char slugged ids (e.g. sc-bs-improved-2026-05). Narrowing
-- to 36 would let a long-id scenario pass validation, write to disk, then
-- fail this insert with a truncation error when a user clicks Analyze
-- (reviewer r2 W1, fixed before deploy).
ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS source_scenario_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_session_source_scenario
    ON t_session (source_scenario_id)
    WHERE source_scenario_id IS NOT NULL;

COMMENT ON COLUMN t_session.source_scenario_id IS
    'EVAL-V2 Q1: id of EvalScenarioEntity / classpath scenario that this session was created to analyze. NULL = regular session.';
