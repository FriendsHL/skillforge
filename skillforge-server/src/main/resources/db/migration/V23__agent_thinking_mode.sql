-- V23__agent_thinking_mode.sql
-- Per-agent thinking_mode + reasoning_effort overrides. Both columns nullable; null
-- means "auto" / provider default (zero backfill needed, baseline-on-migrate = true).
-- CHECK constraints are additive and supported by both PostgreSQL and H2.

ALTER TABLE t_agent ADD COLUMN thinking_mode VARCHAR(16) NULL;
ALTER TABLE t_agent ADD COLUMN reasoning_effort VARCHAR(16) NULL;

ALTER TABLE t_agent ADD CONSTRAINT ck_agent_thinking_mode
    CHECK (thinking_mode IS NULL OR thinking_mode IN ('auto','enabled','disabled'));

ALTER TABLE t_agent ADD CONSTRAINT ck_agent_reasoning_effort
    CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('low','medium','high','max'));
