-- V13: t_skill_draft table for LLM-extracted skill candidates awaiting user review.
-- After user approves, a SkillEntity is created (source='extracted').

CREATE TABLE t_skill_draft (
    id                   VARCHAR(36)   NOT NULL PRIMARY KEY,
    source_session_id    VARCHAR(36),
    owner_id             BIGINT        NOT NULL,
    name                 VARCHAR(256)  NOT NULL,
    description          TEXT,
    triggers             TEXT,
    required_tools       TEXT,
    prompt_hint          TEXT,
    extraction_rationale TEXT,
    status               VARCHAR(32)   NOT NULL DEFAULT 'draft',
    skill_id             BIGINT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    reviewed_at          TIMESTAMPTZ,
    reviewed_by          BIGINT
);

CREATE INDEX idx_skill_draft_owner_status ON t_skill_draft (owner_id, status);
