UPDATE t_agent
SET tool_ids = (tool_ids::jsonb - 'RegisterScriptMethod' - 'RegisterCompiledMethod')::text,
    updated_at = NOW()
WHERE name = 'Main Assistant'
  AND tool_ids IS NOT NULL
  AND BTRIM(tool_ids) <> ''
  AND jsonb_typeof(tool_ids::jsonb) = 'array'
  AND tool_ids::jsonb ?| ARRAY['RegisterScriptMethod', 'RegisterCompiledMethod'];
