-- Keep explicit user-agent allowlists aligned with the new Personal App publisher.
-- NULL/blank/[] retain their existing unrestricted semantics.
UPDATE t_agent
SET tool_ids = (tool_ids::jsonb || '["PublishInteractiveArtifact"]'::jsonb)::text,
    updated_at = NOW()
WHERE agent_type = 'user'
  AND status = 'active'
  AND NULLIF(BTRIM(tool_ids), '') IS NOT NULL
  AND jsonb_typeof(tool_ids::jsonb) = 'array'
  AND jsonb_array_length(tool_ids::jsonb) > 0
  AND NOT (tool_ids::jsonb ? 'PublishInteractiveArtifact');
