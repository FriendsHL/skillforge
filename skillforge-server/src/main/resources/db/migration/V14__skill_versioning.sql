-- V14: Add versioning fields to t_skill.
-- parentSkillId links a forked/improved skill to its source version.
-- semver tracks human-readable version label (v1, v2, ...).
-- usageCount / successCount accumulate execution telemetry for evolution signals.
-- updatedAt is managed by JPA @LastModifiedDate.

ALTER TABLE t_skill
    ADD COLUMN parent_skill_id  BIGINT        DEFAULT NULL,
    ADD COLUMN semver           VARCHAR(32)   DEFAULT NULL,
    ADD COLUMN usage_count      BIGINT        NOT NULL DEFAULT 0,
    ADD COLUMN success_count    BIGINT        NOT NULL DEFAULT 0,
    ADD COLUMN updated_at       TIMESTAMPTZ   DEFAULT NULL;
