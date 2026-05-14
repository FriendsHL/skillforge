-- V77__create_canary_rollout.sql — SKILL-CANARY-ROLLOUT V2 Phase 1.1 schema.
--
-- Two tables backing the canary rollout pipeline (per-agent canary state +
-- hourly aggregated metric snapshots).
--
-- Spec: docs/requirements/active/SKILL-CANARY-ROLLOUT/tech-design.md §2.1
-- Phase: 1.1 (DB schema + Entity + Repository + JPA IT)
--
-- Phase 1.0 校对修正 (2026-05-14):
--   * 字段命名按 SessionSkillResolver 的 skill-name 抽象：
--     baseline_skill_name / candidate_skill_name (VARCHAR(64))
--     不再用原稿的 *_version_id BIGINT — see tech-design.md §0.3 + §5.
--   * 长度 64 与 t_session_annotation.annotation_value 一致（canary_group
--     标签的 value 格式 'skill:<name>' 也复用同样长度）。
--
-- Schema decisions:
--   * TIMESTAMPTZ for all time columns (V70+ convention, Hibernate Instant
--     round-trips cleanly — see V74 header).
--   * BIGINT GENERATED ALWAYS AS IDENTITY (matches V74 / V70 style).
--   * Partial UNIQUE INDEX uq_canary_active enforces "1 active canary per
--     (agent, surface_type)" — ratify decision #4. PostgreSQL only; H2
--     does not support partial unique indices, hence the JPA IT
--     suite (CanaryPersistenceIT) is Testcontainers-PG only.
--   * t_canary_metric_snapshot.canary_id has FK ON DELETE CASCADE so deleting
--     a rollout auto-purges its metric history (V74 pattern).
--   * decimal precision: quality/efficiency 0-100 (5,2) / latency ms (10,2)
--     / cost USD (10,6) — matches EvalScoreFormula M4_V2 axes.
--
-- Idempotency: brand-new tables. Not protected by IF NOT EXISTS — Flyway
-- version uniqueness handles re-runs.

CREATE TABLE t_canary_rollout (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    surface_type             VARCHAR(32)  NOT NULL,   -- skill / future: prompt / behavior_rule
    agent_id                 BIGINT       NOT NULL,
    baseline_skill_name      VARCHAR(64)  NOT NULL,   -- SessionSkillResolver name (not id)
    candidate_skill_name     VARCHAR(64)  NOT NULL,
    rollout_stage            VARCHAR(32)  NOT NULL,   -- disabled / canary / production / rolled_back
    rollout_percentage       INT          NOT NULL DEFAULT 0
        CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100),
    started_at               TIMESTAMPTZ  NOT NULL,
    last_decision_at         TIMESTAMPTZ,
    decision                 VARCHAR(32),             -- promoted / rolled_back / null=ongoing
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_canary_rollout_agent_surface
    ON t_canary_rollout(agent_id, surface_type);
CREATE INDEX idx_canary_rollout_stage
    ON t_canary_rollout(rollout_stage);

-- ratify #4: 同 agent 同 surface 同时只能 1 个 canary (rollout_stage='canary').
-- Other stages (production / rolled_back / disabled) may coexist as history.
CREATE UNIQUE INDEX uq_canary_active
    ON t_canary_rollout(agent_id, surface_type)
    WHERE rollout_stage = 'canary';

CREATE TABLE t_canary_metric_snapshot (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canary_id                   BIGINT       NOT NULL
        REFERENCES t_canary_rollout(id) ON DELETE CASCADE,
    bucket_at                   TIMESTAMPTZ  NOT NULL,  -- hour boundary
    control_sample_size         INT          NOT NULL DEFAULT 0,
    control_success_count       INT          NOT NULL DEFAULT 0,
    control_failure_count       INT          NOT NULL DEFAULT 0,
    control_avg_quality         DECIMAL(5,2),
    control_avg_efficiency      DECIMAL(5,2),
    control_avg_latency         DECIMAL(10,2),
    control_avg_cost            DECIMAL(10,6),
    candidate_sample_size       INT          NOT NULL DEFAULT 0,
    candidate_success_count     INT          NOT NULL DEFAULT 0,
    candidate_failure_count     INT          NOT NULL DEFAULT 0,
    candidate_avg_quality       DECIMAL(5,2),
    candidate_avg_efficiency    DECIMAL(5,2),
    candidate_avg_latency       DECIMAL(10,2),
    candidate_avg_cost          DECIMAL(10,6),
    fail_rate_ratio             DECIMAL(6,3),  -- candidate / control, auto-rollback trigger
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_metric_canary_bucket UNIQUE (canary_id, bucket_at)
);
CREATE INDEX idx_metric_canary_bucket
    ON t_canary_metric_snapshot(canary_id, bucket_at DESC);
