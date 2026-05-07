-- V61__create_mcp_server.sql — P11 MCP Client MVP
--
-- One row per externally-spawned MCP stdio server. The application's
-- McpServerLifecycle bean reads enabled rows on startup, spawns each as a
-- subprocess, runs the JSON-RPC initialize / tools/list handshake, and
-- registers each advertised tool into the SkillRegistry under the name
-- mcp_<server>_<tool> (INV-3).
--
-- Schema decisions (matching project conventions):
--   * args / env stored as TEXT holding canonical JSON (matches EvalScenarioEntity
--     and ScheduledTaskEntity.channel_target — service layer owns parse/serialize
--     via the Spring-managed ObjectMapper). Avoids JSONB <-> Hibernate friction
--     and keeps Flyway migrations agnostic to PG-specific JSONB ops.
--   * name is the per-process unique identifier and the prefix in tool names;
--     CHECK constraint enforces [a-z0-9_]+ shape so the resulting tool name is a
--     legal identifier downstream.
--   * description is operator-facing only (UI list page).
--   * created_at / updated_at use TIMESTAMPTZ for tz-correct audit (project
--     convention since V59).
--
-- Indexes:
--   * UNIQUE (name) — used by CRUD lookup + ON CONFLICT seed below.
--   * idx_mcp_server_enabled — McpServerLifecycle.onApplicationReady scan.
--
-- Agent-side wiring:
--   ALTER TABLE t_agent ADD mcp_server_ids VARCHAR(512) NOT NULL DEFAULT ''.
--   Comma-separated list of mcp server names (matching t_mcp_server.name) that
--   this agent has explicitly enabled. Empty string = no MCP tools exposed.
--   Convention matches existing skill_ids.

CREATE TABLE IF NOT EXISTS t_mcp_server (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL UNIQUE,
    command      VARCHAR(256) NOT NULL,
    args         TEXT         NOT NULL DEFAULT '[]',
    env          TEXT         NOT NULL DEFAULT '{}',
    description  TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mcp_server_name_shape
        CHECK (name ~ '^[a-z0-9_]+$' AND length(name) BETWEEN 1 AND 32)
);

CREATE INDEX IF NOT EXISTS idx_mcp_server_enabled
    ON t_mcp_server (enabled);

COMMENT ON COLUMN t_mcp_server.name IS
    'Stable identifier; used as <server> in mcp_<server>_<tool> tool registration. '
    'CHECK enforces [a-z0-9_]+ length<=32 (INV-3).';
COMMENT ON COLUMN t_mcp_server.args IS
    'JSON array of process args (e.g. ["-y", "@modelcontextprotocol/server-time"]). '
    'Stored as TEXT; service layer parses via ObjectMapper.';
COMMENT ON COLUMN t_mcp_server.env IS
    'JSON object of env vars; values may use dollar-brace placeholders (e.g. dollar-brace TOKEN brace) '
    'that the lifecycle layer resolves from System.getenv at process spawn time (INV-5). '
    'Unresolved placeholders are passed through unchanged (subprocess sees the literal).';

-- agent.mcp_server_ids — comma-separated whitelist of server names this agent enables.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 't_agent' AND column_name = 'mcp_server_ids'
    ) THEN
        ALTER TABLE t_agent
            ADD COLUMN mcp_server_ids VARCHAR(512) NOT NULL DEFAULT '';
        COMMENT ON COLUMN t_agent.mcp_server_ids IS
            'Comma-separated list of mcp server names (matching t_mcp_server.name) that '
            'this agent enables. Same convention as skill_ids. Empty string = no MCP servers '
            'enabled (INV-4 default).';
    END IF;
END $$;

-- Default seed: time MCP server (idempotent — name UNIQUE + ON CONFLICT).
-- 'uvx mcp-server-time' is Anthropic's reference time server (Python; also runnable
-- via `pipx run mcp-server-time` if uvx is unavailable). Zero-config, no auth,
-- two tools: get_current_time, convert_time. INV-2 (best-effort startup) means
-- if uvx isn't installed on the host, lifecycle logs a warning and continues.
-- Operator can swap to a different command via the dashboard.
INSERT INTO t_mcp_server (name, command, args, env, description, enabled)
VALUES (
    'time',
    'uvx',
    '["mcp-server-time"]',
    '{}',
    'Anthropic 官方 time MCP server: 时区转换 / 当前时间 (零配置无 auth, 需要 uvx)',
    TRUE
) ON CONFLICT (name) DO NOTHING;
