-- V159__acp_cc_event.sql — ACP-EXTERNAL-AGENT P2-1
--
-- Stores normalized OTLP log/events emitted by a spawned Claude Code (cc) child
-- process and bound back to the SkillForge cc sub-session via the injected
-- `sf.session_id` resource attribute (see AcpAgentRunner telemetry env injection
-- + OtlpIngestService binding/PII-filter).
--
-- Spike-verified (2026-06-19, /tmp/acp-spike/otel-spike.mjs): cc emits OTLP
-- metrics + logs(events) — NO traces/spans. P2-1 ingests the logs only.
--
-- PRIVACY: this table NEVER stores PII (user.email / user.account_uuid /
-- user.account_id / user.id / organization.id) nor the `prompt` full text — the
-- ingest layer strips those before persisting; only structural attrs land in
-- attrs_json (model / tokens / cost / durations / tool_name / tool_use_id /
-- agent.name / sizes / success / agent_type / total_* / prompt_length / ...).
--
-- ABUSE GUARD: rows are only written for events whose `sf.session_id` matches an
-- existing t_session row (FK below + an existence check in the ingest layer),
-- so the unauthenticated receiver can not be spammed into unbounded growth.

CREATE TABLE t_acp_cc_event (
    id              BIGSERIAL    NOT NULL PRIMARY KEY,
    session_id      VARCHAR(36)  NOT NULL,             -- SkillForge cc sub-session id (sf.session_id)
    cc_session_id   VARCHAR(128),                      -- cc's own session.id (nullable; structural)
    event_name      VARCHAR(64)  NOT NULL,             -- e.g. claude_code.api_request
    event_seq       BIGINT,                            -- event.sequence (nullable)
    ts              TIMESTAMPTZ,                        -- event.timestamp / logRecord time (nullable)
    agent_name      VARCHAR(128),                      -- agent.name on subagent api_request (nullable)
    tool_name       VARCHAR(128),                      -- tool_name on tool_* events (nullable)
    tool_use_id     VARCHAR(128),                      -- tool_use_id on tool_* events (nullable)
    attrs_json      TEXT         NOT NULL,             -- PII-filtered structural attributes (JSON object)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_acp_cc_event_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE
);

-- Read events for one cc sub-session in arrival order (P2-2 trace rebuild reads this).
CREATE INDEX idx_acp_cc_event_session
    ON t_acp_cc_event (session_id, id);
