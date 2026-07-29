-- Keep the AnySearch MCP server and agent bindings, but remove the long routing
-- handbook previously injected into the system prompt by V154. Tool discovery
-- and invocation continue through the registered MCP Tool descriptions/schemas.
UPDATE t_agent
SET tools_prompt = NULL,
    updated_at = NOW()
WHERE id IN (3, 5)
  AND tools_prompt LIKE '## Search tool routing (IMPORTANT)%';
