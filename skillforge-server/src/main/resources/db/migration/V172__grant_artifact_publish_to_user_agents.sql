-- Grant outbound chat artifact publishing to every active user-facing agent
-- that uses an explicit, non-empty tool allowlist. NULL/blank/[] retain their
-- existing unrestricted semantics; system agents remain least-privileged.
UPDATE t_agent
SET tool_ids = (tool_ids::jsonb || '["PublishChatArtifact"]'::jsonb)::text,
    updated_at = NOW()
WHERE agent_type = 'user'
  AND status = 'active'
  AND NULLIF(BTRIM(tool_ids), '') IS NOT NULL
  AND jsonb_typeof(tool_ids::jsonb) = 'array'
  AND jsonb_array_length(tool_ids::jsonb) > 0
  AND NOT (tool_ids::jsonb ? 'PublishChatArtifact');
