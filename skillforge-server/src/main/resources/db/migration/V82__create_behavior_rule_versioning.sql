-- V82__create_behavior_rule_versioning.sql — MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 schema.
--
-- Two tables backing the third optimizable surface (behavior_rule), mirroring
-- the V3 PromptVersion + PromptAbRun shape so the V4 AbstractAbEvalRunner can
-- treat all three surfaces uniformly in Phase 1.2.
--
-- Spec: docs/requirements/active/MULTI-SURFACE-FLYWHEEL/tech-design.md §4.1
-- Phase: 1.1 (DB schema + Entity + Repository + JPA IT)
--
-- Schema decisions:
--   * TIMESTAMPTZ for all time columns (V70+ convention — Hibernate Instant
--     round-trips cleanly; see V77 / V80 headers).
--   * VARCHAR(36) PK for UUID identity (mirrors PromptVersion / PromptAbRun).
--   * agent_id VARCHAR(36) — kept as String for surface symmetry with
--     PromptVersionEntity (prompt surface stores Long.toString). The V77
--     canary table uses BIGINT for agent_id; that mismatch is a known
--     historical inconsistency we don't widen in V4 since BehaviorRuleSurface
--     never joins these tables in Phase 1.1.
--   * Partial UNIQUE INDEX uq_brv_one_active enforces "≤1 active behavior_rule
--     version per agent" (PG only — H2 doesn't support partial unique, hence
--     BehaviorRulePersistenceIT runs in Testcontainers PG only — same pattern
--     as CanaryPersistenceIT).
--   * source_event_id is a soft FK to t_optimization_event.id (no DB FK,
--     avoiding cross-module schema coupling per tech-design §4.1).
--   * status values: 'candidate' / 'active' / 'retired' / 'rejected'.
--   * source values: 'manual' / 'attribution' / 'auto_improve'.
--
-- Idempotency: brand-new tables. Not protected by IF NOT EXISTS — Flyway
-- version uniqueness handles re-runs.

CREATE TABLE t_behavior_rule_version (
    id                      VARCHAR(36)  PRIMARY KEY,
    agent_id                VARCHAR(36)  NOT NULL,
    version_number          INT          NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'candidate',
    -- JSON array per behavior-rules.json schema:
    --   [{ id, priority, when, then, rationale }]
    rules_json              TEXT         NOT NULL,
    source                  VARCHAR(32)  NOT NULL DEFAULT 'manual',
    improvement_rationale   TEXT,
    source_event_id         BIGINT,                          -- soft FK → t_optimization_event.id
    baseline_version_id     VARCHAR(36),                     -- baseline this candidate was derived from
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    promoted_at             TIMESTAMPTZ
);

CREATE INDEX idx_brv_agent_status ON t_behavior_rule_version(agent_id, status);
CREATE INDEX idx_brv_active       ON t_behavior_rule_version(agent_id) WHERE status = 'active';

-- ratify decision: ≤1 active behavior_rule version per agent at any moment
-- (mirrors V3 prompt surface — exactly one production version is the truth).
-- Partial UNIQUE only fires on rows with status='active'; retired / candidate
-- rows may coexist freely as history.
CREATE UNIQUE INDEX uq_brv_one_active
    ON t_behavior_rule_version(agent_id)
    WHERE status = 'active';

CREATE TABLE t_behavior_rule_ab_run (
    id                       VARCHAR(36)  PRIMARY KEY,
    agent_id                 VARCHAR(36)  NOT NULL,
    baseline_version_id      VARCHAR(36)  NOT NULL,
    candidate_version_id     VARCHAR(36)  NOT NULL,
    baseline_eval_run_id     VARCHAR(36),                    -- optional eval anchor
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    baseline_pass_rate       DOUBLE PRECISION,
    candidate_pass_rate      DOUBLE PRECISION,
    delta_pass_rate          DOUBLE PRECISION,
    promoted                 BOOLEAN      NOT NULL DEFAULT FALSE,
    failure_reason           TEXT,
    ab_scenario_results_json TEXT,
    triggered_by_user_id     BIGINT,
    started_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at             TIMESTAMPTZ
);

CREATE INDEX idx_brar_agent_status ON t_behavior_rule_ab_run(agent_id, status);
CREATE INDEX idx_brar_candidate    ON t_behavior_rule_ab_run(candidate_version_id);
