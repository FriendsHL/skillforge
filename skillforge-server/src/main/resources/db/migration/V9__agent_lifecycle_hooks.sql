-- N3: Agent lifecycle hooks column
ALTER TABLE t_agent ADD COLUMN IF NOT EXISTS lifecycle_hooks TEXT;

COMMENT ON COLUMN t_agent.lifecycle_hooks IS
    'JSON: {"version":1,"hooks":{"SessionStart":[HookEntry...],"UserPromptSubmit":[...],"PostToolUse":[...],"Stop":[...],"SessionEnd":[...]}}';
