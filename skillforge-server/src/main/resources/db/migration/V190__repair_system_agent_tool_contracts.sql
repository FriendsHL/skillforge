-- Make system-agent prompt dependencies explicit and keep their runtime tool
-- allowlists aligned with those dependencies.

WITH annotator AS (
    SELECT id,
           CASE
               WHEN COALESCE(NULLIF(tool_ids, ''), '[]')::jsonb ? 'SessionAnnotationRead'
                   THEN COALESCE(NULLIF(tool_ids, ''), '[]')::jsonb
               ELSE COALESCE(NULLIF(tool_ids, ''), '[]')::jsonb
                       || '["SessionAnnotationRead"]'::jsonb
           END AS tools
    FROM t_agent
    WHERE name = 'session-annotator'
)
UPDATE t_agent agent
SET tool_ids = annotator.tools::text,
    config = jsonb_set(
            jsonb_set(
                    COALESCE(NULLIF(agent.config, '')::jsonb, '{}'::jsonb),
                    '{tool_ids}',
                    annotator.tools,
                    true
            ),
            '{required_tool_ids}',
            annotator.tools,
            true
    )::text,
    updated_at = NOW()
FROM annotator
WHERE agent.id = annotator.id;

WITH curator AS (
    SELECT '["ListActiveUsers","ListMemoryCandidates","ListRecentSessionTranscripts","ClusterMemories","CreateMemoryProposal","SubAgent"]'::jsonb AS tools
)
UPDATE t_agent agent
SET tool_ids = curator.tools::text,
    config = jsonb_set(
            jsonb_set(
                    COALESCE(NULLIF(agent.config, '')::jsonb, '{}'::jsonb),
                    '{tool_ids}',
                    curator.tools,
                    true
            ),
            '{required_tool_ids}',
            curator.tools,
            true
    )::text,
    system_prompt = replace(
            agent.system_prompt,
            'call `SubAgent` and dispatch one sub-session:',
            'call `SubAgent(action="dispatch", agentName="memory-curator", task="Run memory dreaming for userId=<id>. Use ListMemoryCandidates, ListRecentSessionTranscripts, ClusterMemories, and CreateMemoryProposal to produce evidence-backed proposals.")`:'
    ),
    updated_at = NOW()
FROM curator
WHERE agent.name = 'memory-curator';

UPDATE t_scheduled_task
SET prompt_template = replace(
        prompt_template,
        'SubAgent per user',
        'SubAgent(action="dispatch", agentName="memory-curator", task="Run memory dreaming for userId=<id>") per user'
    ),
    updated_at = NOW()
WHERE name = 'memory-curator nightly';
