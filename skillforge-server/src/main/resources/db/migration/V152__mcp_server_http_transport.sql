-- V152__mcp_server_http_transport.sql — MCP HTTP (Streamable HTTP) transport
--
-- Adds a second transport kind to t_mcp_server. Until now every row was a stdio
-- subprocess (command + args + env). An http row instead targets a remote
-- Streamable HTTP endpoint (url + headers); command/args are unused.
--
-- Columns:
--   * transport — 'stdio' (default, backward-compatible) or 'http'. Immutable
--     post-create at the service layer (too many invariants pin to it).
--   * url       — endpoint for http transport; NULL for stdio.
--   * headers   — JSON object of HTTP headers (e.g. Authorization: Bearer dollar-brace
--     KEY brace), same dollar-brace placeholder semantics as env. Defaults to '{}'.
--     (Flyway expands dollar-brace tokens even inside SQL comments, so this text spells
--     them out rather than writing the literal — see V61's COMMENT for the same trick.)
--
-- command was VARCHAR(256) NOT NULL; an http row has no command, so we drop the
-- NOT NULL and add a CHECK that enforces the per-transport required field instead:
--   stdio → command present; http → url present.
--
-- Existing 'time' stdio row keeps transport='stdio' (column default) + its command,
-- so it satisfies the new CHECK with zero data migration. embedded zonky PG = PG14,
-- CHECK constraints fully supported.

ALTER TABLE t_mcp_server ADD COLUMN transport VARCHAR(16) NOT NULL DEFAULT 'stdio';
ALTER TABLE t_mcp_server ADD COLUMN url TEXT;
ALTER TABLE t_mcp_server ADD COLUMN headers TEXT NOT NULL DEFAULT '{}';

ALTER TABLE t_mcp_server ALTER COLUMN command DROP NOT NULL;

ALTER TABLE t_mcp_server ADD CONSTRAINT chk_mcp_transport
    CHECK (
        (transport = 'stdio' AND command IS NOT NULL AND command <> '')
     OR (transport = 'http'  AND url     IS NOT NULL AND url     <> '')
    );

COMMENT ON COLUMN t_mcp_server.transport IS
    'Transport kind: stdio (subprocess NDJSON) or http (Streamable HTTP POST). '
    'Immutable post-create. CHECK chk_mcp_transport pairs it with command/url presence.';
COMMENT ON COLUMN t_mcp_server.url IS
    'Endpoint URL for http transport; NULL for stdio.';
COMMENT ON COLUMN t_mcp_server.headers IS
    'JSON object of HTTP headers for http transport; values may use dollar-brace '
    'placeholders (e.g. dollar-brace KEY brace) resolved from System.getenv at connect '
    'time (same semantics as env). Stored as TEXT; service layer parses via ObjectMapper.';
