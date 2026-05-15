-- V83__multi_surface_attribution_link.sql — MULTI-SURFACE-FLYWHEEL V4 Phase 1.3
--
-- Two schema changes, both groundwork for V4's behavior_rule surface joining
-- the V3 attribution → A/B → canary → promote loop:
--
-- 1. t_optimization_event.candidate_behavior_rule_version_id (VARCHAR(36))
--    — type-safe link from an attribution event to the
--    {@link BehaviorRuleVersionEntity} produced by
--    AttributionApprovalService.dispatchBehaviorRuleSurface. Mirrors the
--    pre-existing candidate_skill_id (BIGINT) + candidate_prompt_version_id
--    (BIGINT) columns conceptually, but **VARCHAR(36)** because
--    BehaviorRuleVersionEntity.id is a UUID string (per V82 schema). This
--    makes the V3.2 stage-mirror listener (BehaviorRulePromotedEvent →
--    stage=promoted) a clean lookup-by-id rather than parsing a description
--    prefix.
--
-- 2. uq_canary_active partial UNIQUE INDEX — tightened from
--    (agent_id, surface_type) to (agent_id) per ratify #4: "同 agent 1 active
--    canary，跨所有 surface". V77's per-surface variant allowed concurrent
--    canaries across skill + prompt + behavior_rule on the same agent, which
--    creates confounding when interpreting eval deltas (which surface drove
--    the change?). V4 enforces serial canaries instead.
--
--    Practical impact in Phase 1.3 is zero (V2's CanaryRolloutService still
--    only accepts surfaceType='skill' so multi-surface canaries can't be
--    created via the service yet). The constraint is the schema-side
--    pre-commitment for Phase 1.4 dashboard wiring.
--
-- Spec: docs/requirements/active/MULTI-SURFACE-FLYWHEEL/tech-design.md §4 / §5.2
-- Phase: 1.3 (V3 attribution dispatch behavior_rule branch + canary generic)
--
-- Idempotency: ALTER ADD COLUMN + DROP/CREATE INDEX; Flyway version uniqueness
-- prevents re-run. ADD COLUMN is fast on an essentially-empty t_optimization_event
-- (V80 dogfood data only). The partial UNIQUE change requires no active row
-- to violate it; at migration time there are no behavior_rule canaries (Phase
-- 1.3 hasn't shipped) so it's safe.

ALTER TABLE t_optimization_event
    ADD COLUMN candidate_behavior_rule_version_id VARCHAR(36);

CREATE INDEX idx_oe_candidate_brv_id
    ON t_optimization_event(candidate_behavior_rule_version_id)
    WHERE candidate_behavior_rule_version_id IS NOT NULL;

-- ratify #4: 同 agent 1 active canary，跨所有 surface — drop the V77 per-surface
-- variant and recreate without the surface_type column in the index key.
DROP INDEX IF EXISTS uq_canary_active;
CREATE UNIQUE INDEX uq_canary_active
    ON t_canary_rollout(agent_id)
    WHERE rollout_stage = 'canary';
