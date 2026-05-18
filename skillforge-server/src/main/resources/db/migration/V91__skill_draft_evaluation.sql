-- SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18):
-- Add 4 columns to t_skill_draft so the evaluation-gate on 4 skill-creation
-- entry-points (upload / marketplace / natural-language / extract-from-sessions)
-- has somewhere to persist:
--   (1) target_agent_id    — which agent's loop will host the with/without sandbox
--   (2) candidate_skill_id — id of the transient SkillEntity rendered for the eval
--   (3) source             — provenance enum (4 entry-points + legacy 'attribution' / 'manual')
--   (4) evaluation_result_json — benchmark + LLM summary blob written by
--       SkillCreatorEvalCoordinator after all 2N SubAgent runs land
--
-- All nullable — pre-existing rows (extract / attribution path) keep working
-- without back-fill. Phase 1.2 hook callers will populate on new write paths.
--
-- Companion migration V92 adds t_session.skill_overrides_json which the
-- SubAgent dispatch path stamps so ChatService.runLoop can swap agent.skillIds
-- with the eval-time override (with_skill / without_skill) without mutating
-- the persistent t_agent row.

ALTER TABLE t_skill_draft ADD COLUMN target_agent_id BIGINT NULL;
ALTER TABLE t_skill_draft ADD COLUMN candidate_skill_id BIGINT NULL;
ALTER TABLE t_skill_draft ADD COLUMN source VARCHAR(64) NULL;
ALTER TABLE t_skill_draft ADD COLUMN evaluation_result_json TEXT NULL;

-- evaluation_result_json shape (Jackson-serialised EvaluationResult record):
-- {
--   "with_skill":    { "compositeScore": 0.85, "overallScore": 0.78, "passRate": 0.85, "avgLatencyMs": 4500, "totalCostUsd": 0.0023 },
--   "without_skill": { "compositeScore": 0.42, "overallScore": 0.45, "passRate": 0.33, "avgLatencyMs": 3200, "totalCostUsd": 0.0011 },
--   "delta":         { "compositeScore": 0.43, "overallScore": 0.33, "passRate": 0.52, "avgLatencyMs": 1300, "totalCostUsd": 0.0012 },
--   "llmSummary":    "...",
--   "sourceSessionIds": [ "sess-..." ],
--   "scenarioCount":   3,
--   "evaluatedAt":     "2026-05-18T10:23:45Z",
--   "evaluatorVersion": "skill-creator-1.0"
-- }
--
-- source enum (free-form VARCHAR; no CHECK constraint to keep migration flexible):
--   'upload'                    — entry 1 (SkillService.uploadSkill)
--   'marketplace'               — entry 2 (SkillImportService.importSkill)
--   'natural-language'          — entry 3 (skill-creator skill SubAgent path)
--   'extract-from-sessions'     — entry 4 (SkillDraftService.extractFromRecentSessions)
--   'attribution'               — legacy (pre-Phase 1.1)
--   'manual'                    — operator hand-create (future)
--
-- status enum (existing column, no schema change here; widened semantically):
--   'draft'            — pre-evaluation, unchanged
--   'evaluated_passed' — Phase 1.1 add: delta passed threshold (operator can promote)
--   'rejected'         — Phase 1.1 add: delta below threshold (LLM summary explains)
--   'approved'         — existing
--   'discarded'        — existing
