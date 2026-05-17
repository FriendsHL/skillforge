-- V88__add_candidate_uuid_sidecar_columns.sql
--
-- FLYWHEEL-LOOP-CLOSURE Phase 1.2 (2026-05-16): close V3.2 "missing setBack"
-- on candidate links. Phase 1.0 root-cause investigation showed the perceived
-- bug (event 31 with candidate_prompt_version_id=NULL post-approve) is a type
-- mismatch — PromptVersionEntity.id and SkillDraftEntity.id are String UUIDs
-- while the existing candidate_*_id columns are BIGINT. The existing
-- AttributionApprovalService L346-349 / L328-331 comments explicitly call out
-- "can't fit a UUID into BIGINT — log the link for now".
--
-- Resolution (tech-design.md ratify #5): add two nullable VARCHAR(36) sidecar
-- columns so the UUID surface (Phase 1.4 /run-ab + Phase 1.3 listener) can
-- read back the link. The old BIGINT columns stay untouched for backward
-- compatibility — they will be populated later in the lifecycle when a
-- SkillDraft merges into a SkillEntity (BIGINT PK) at approveDraft time.
-- behavior_rule already uses VARCHAR(36) (added in V83) — no change there.
--
-- Zero data migration: existing rows get NULL on both new columns; the
-- attribution flow writes them on the next approve / retry.

ALTER TABLE t_optimization_event
    ADD COLUMN candidate_prompt_version_uuid VARCHAR(36) NULL,
    ADD COLUMN candidate_skill_draft_uuid    VARCHAR(36) NULL;
