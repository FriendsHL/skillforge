-- V47: Rename built-in file tools FileRead/FileWrite/FileEdit -> Read/Write/Edit
-- in seeded t_agent.skill_ids JSON array strings (TOOL-CC-NAME-ALIAS).
--
-- Background: non-Anthropic models (mimo / DeepSeek / qwen) hallucinate the
-- short Claude Code names (Read / Write / Edit) directly. SkillForge's tool
-- registry previously used "FileRead" / "FileWrite" / "FileEdit" as the
-- registered names, so dispatcher rejected those calls until the LLM
-- self-corrected. We renamed the registered tool names; this migration
-- backfills already-seeded agents.
--
-- Note on column: the seed migrations V19/V26/V27 populate t_agent.skill_ids
-- (NOT t_agent.tool_ids — tool_ids is the optional allowedToolNames filter
-- and is NULL for the seeded rows). The agent definition resolves the tool
-- list from skill_ids, so updating skill_ids is what makes the renamed tools
-- reach the running agents. Both columns are updated defensively so any row
-- that happens to mention the old names in tool_ids is also fixed.
--
-- Why explicit-quote REPLACE: the JSON array stores names in double quotes
-- (e.g. ["FileRead","Bash"]). Replacing the quoted form '"FileRead"' avoids
-- accidentally rewriting future names that share the substring (e.g. a
-- hypothetical "FileReadConfig"). Order matters: replace FileRead* first
-- only if the substring would match — here the names are distinct so order
-- is not actually overlapping, but we still apply each in a separate
-- statement for readability.

UPDATE t_agent
SET skill_ids = REPLACE(skill_ids, '"FileRead"', '"Read"')
WHERE skill_ids LIKE '%"FileRead"%';

UPDATE t_agent
SET skill_ids = REPLACE(skill_ids, '"FileWrite"', '"Write"')
WHERE skill_ids LIKE '%"FileWrite"%';

UPDATE t_agent
SET skill_ids = REPLACE(skill_ids, '"FileEdit"', '"Edit"')
WHERE skill_ids LIKE '%"FileEdit"%';

-- Defensive: same rewrite on tool_ids in case any operator-created agent
-- stored the old name in the allowedToolNames filter.
UPDATE t_agent
SET tool_ids = REPLACE(tool_ids, '"FileRead"', '"Read"')
WHERE tool_ids LIKE '%"FileRead"%';

UPDATE t_agent
SET tool_ids = REPLACE(tool_ids, '"FileWrite"', '"Write"')
WHERE tool_ids LIKE '%"FileWrite"%';

UPDATE t_agent
SET tool_ids = REPLACE(tool_ids, '"FileEdit"', '"Edit"')
WHERE tool_ids LIKE '%"FileEdit"%';
