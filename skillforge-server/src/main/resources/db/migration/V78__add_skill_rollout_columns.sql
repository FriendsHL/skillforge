-- V78__add_skill_rollout_columns.sql — SKILL-CANARY-ROLLOUT V2 Phase 1.1.
--
-- Adds 2 rollout columns to t_skill, backing the runtime canary allocator
-- + dashboard rollout panel.
--
-- Spec: docs/requirements/active/SKILL-CANARY-ROLLOUT/tech-design.md §2.2
-- Phase: 1.1 (Entity wiring; allocator + service land in Phase 1.2 / 1.3).
--
-- Default values 'production' / 100 = backward-compatible "一刀切" mode:
-- existing skills auto-fall-through CanaryAllocator with 100% → baseline
-- path, leaving the SkillAbEvalService.promoteCandidate pipeline untouched.
-- No production rollout/canary state exists at boot; operators opt-in via
-- the dashboard "start canary" button (Phase 1.3 endpoint).

ALTER TABLE t_skill
    ADD COLUMN rollout_stage VARCHAR(32) NOT NULL DEFAULT 'production';

ALTER TABLE t_skill
    ADD COLUMN rollout_percentage INT NOT NULL DEFAULT 100
        CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100);
