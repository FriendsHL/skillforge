-- V5 EVAL-DYNAMIC-USER-SIM Phase 1.1
-- Adds 6 business-semantic columns to t_eval_scenario for dynamic UserSim trial
-- driving (Phase 2/3 F1). All nullable for backward compat with historical
-- scenarios extracted before V84 — legacy rows leave the 6 columns NULL and
-- existing eval flows continue to work.
--
-- Columns:
--   * business_goal      — 用户真正要达成的业务目标 (1 句话)
--   * success_criteria   — 完成的客观可验证标准
--   * user_persona       — 用户画像 (角色 / 性格 / 技术水平)
--   * user_constraints   — 隐性约束 (不能做的事 / 必须遵守的规则)
--   * failure_signals    — 失败信号 (用户什么样的行为代表放弃 / 不满意)
--   * expected_outcome   — 期望最终结果 (理想路径)
--
-- These are populated by SessionScenarioExtractorService.extractFromSessions
-- (LLM batch path, switched to xiaomi-mimo / mimo-v2.5-pro hardcoded per V5
-- ratify #5b). Used by V5 Phase 1.2 UserSimulatorAgent to drive multi-turn
-- trials with persona + business goal context.

ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS business_goal TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS success_criteria TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS user_persona TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS user_constraints TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS failure_signals TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN IF NOT EXISTS expected_outcome TEXT;
