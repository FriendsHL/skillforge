-- Add memory-context read access for the attribution flywheel without
-- disturbing existing required tools from V81/V94. Keep top-level tool_ids and
-- config.tool_ids in sync because different runtime paths have used both.
WITH target AS (
    SELECT
        id,
        CASE
            WHEN COALESCE(tool_ids, '') = ''
                THEN '["ListRelevantMemories"]'
            WHEN tool_ids::jsonb ? 'ListRelevantMemories'
                THEN tool_ids
            ELSE (tool_ids::jsonb || '["ListRelevantMemories"]'::jsonb)::text
        END AS next_tool_ids
    FROM t_agent
    WHERE name IN ('attribution-dispatcher', 'attribution-curator')
)
UPDATE t_agent a
SET
    tool_ids = target.next_tool_ids,
    config = jsonb_set(
        COALESCE(NULLIF(a.config, '')::jsonb, '{}'::jsonb),
        '{tool_ids}',
        target.next_tool_ids::jsonb,
        true
    )::text,
    updated_at = NOW()
FROM target
WHERE a.id = target.id;
