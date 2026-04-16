-- N2: Agent behavior rules column
ALTER TABLE t_agent ADD COLUMN IF NOT EXISTS behavior_rules TEXT;

COMMENT ON COLUMN t_agent.behavior_rules IS
    'JSON: {"builtinRuleIds":["rule-id",...],"customRules":["text",...]}';
